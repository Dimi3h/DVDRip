package be.discrip.drive

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import be.discrip.core.disc.DiscInfo
import be.discrip.core.disc.DiscState
import be.discrip.core.disc.DriveMonitor
import be.discrip.core.disc.DriveProblem
import be.discrip.core.disc.DriveReader
import be.discrip.core.disc.DriveUnresponsiveException
import be.discrip.core.scsi.InquiryData
import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.usb.BulkOnlyTransport
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException
import be.discrip.rip.DriveDisc
import be.discrip.usb.AndroidBulkTransport
import be.discrip.usb.LibusbTransport
import be.discrip.usb.MassStorageInterface
import be.discrip.usb.MassStorageTransport
import be.discrip.usb.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Finds the USB DVD drive, asks for permission, claims it and follows the disc in it.
 *
 * Everything that touches the device runs on one thread, so commands never interleave and the
 * state below needs no locks; the rip engine's reads hop onto it too ([lease]). Claiming detaches
 * the kernel's own driver (force = true), which for a USB stick means Android unmounts it; DiscRip
 * therefore only claims a device after the user granted it permission and lets go at once when
 * INQUIRY shows it is not a CD/DVD drive.
 */
class DriveManager(context: Context, private val scope: CoroutineScope) {

    private val context = context.applicationContext
    private val usbManager = this.context.getSystemService(UsbManager::class.java)
    private val hasUsbHost = this.context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) && usbManager != null
    private val nonOptical = NonOpticalDevices(this.context)
    @Volatile
    private var driveThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "usb-drive").also { driveThread = it } }
    private val dispatcher = executor.asCoroutineDispatcher()

    private val statusState = MutableStateFlow<DriveStatus>(if (hasUsbHost) DriveStatus.NoDrive else DriveStatus.UsbHostUnsupported)
    val status: StateFlow<DriveStatus> = statusState.asStateFlow()

    private val messageChannel = Channel<DriveMessage>(Channel.BUFFERED)
    val messages: Flow<DriveMessage> = messageChannel.receiveAsFlow()

    // Confined to the drive thread.
    private var open: OpenDrive? = null
    private var lease: Lease? = null
    private val denied = mutableSetOf<String>()

    /** Devices of this session that are not optical drives, by usbfs path. */
    private val notOptical = mutableSetOf<String>()

    private class OpenDrive(
        val deviceName: String,
        val transport: MassStorageTransport,
        val drive: OpticalDrive,
        val monitor: DriveMonitor,
        val info: DriveInfo,
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onDrive { refresh() }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDrive { detached(device?.deviceName) }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) onDrive { permissionAnswered(device, granted) }
                }
            }
        }
    }

    init {
        if (hasUsbHost) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            ContextCompat.registerReceiver(this.context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    /**
     * Polls the drive for as long as the calling coroutine runs, typically while the app is in
     * the foreground. The connection itself stays open in between.
     */
    suspend fun watch() {
        if (!hasUsbHost) return
        withContext(dispatcher) {
            refresh()
            while (true) {
                poll()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * The system opened DiscRip for a device the user just plugged in, granting permission. The
     * app may not have been running to see the broadcast, so look again.
     */
    fun deviceAttached() = onDrive { refresh() }

    /** The user asked to use the attached device: ask for permission if needed, then connect. */
    fun connect(deviceName: String) = onDrive {
        val device = usbManager.deviceList[deviceName] ?: return@onDrive refresh()
        denied -= deviceName
        if (usbManager.hasPermission(device)) connect(device, userAsked = true) else requestPermission(device)
    }

    fun eject() = onDrive {
        val drive = open ?: return@onDrive
        if (lease != null) return@onDrive
        try {
            drive.monitor.eject()
            publish(drive)
        } catch (e: IOException) {
            Log.w(TAG, "Eject failed", e)
            messageChannel.trySend(DriveMessage.EJECT_FAILED)
        }
    }

    /** Closes the tray; many slim USB drives cannot, and the user is told to push it in. */
    fun closeTray() = onDrive {
        val drive = open ?: return@onDrive
        if (lease != null) return@onDrive
        try {
            drive.drive.load()
        } catch (e: IOException) {
            Log.w(TAG, "Loading the tray failed", e)
            messageChannel.trySend(DriveMessage.CLOSE_TRAY_FAILED)
        }
        poll()
    }

    /** Reads the disc again after it could not be used. */
    fun retryDisc() = onDrive {
        if (lease != null) return@onDrive
        open?.monitor?.invalidate()
        poll()
    }

    /**
     * Lends the disc [insertion] ([DiscState.Ready.insertion]) to the rip engine, or null when
     * that disc is no longer in the drive or is lent already. Until the loan is closed the drive is
     * not polled, so the engine's reads see every medium change themselves. Blocks.
     */
    fun lease(insertion: Long): DriveDisc? = onDriveBlocking {
        val drive = open ?: return@onDriveBlocking null
        val ready = drive.monitor.state as? DiscState.Ready
        if (ready == null || ready.insertion != insertion || lease != null) return@onDriveBlocking null
        val reader = try {
            DriveReader.open(drive.drive, ready.disc.blockCount)
        } catch (e: IOException) {
            Log.w(TAG, "The disc cannot be read", e)
            return@onDriveBlocking null
        }
        Lease(drive, ready.disc, reader).also {
            lease = it
            publish(drive)
        }
    }

    private fun onDrive(block: () -> Unit) {
        scope.launch(dispatcher) { block() }
    }

    /** Runs [block] on the drive thread and waits for it; exceptions come back as they are. */
    private fun <T> onDriveBlocking(block: () -> T): T {
        if (Thread.currentThread() === driveThread) return block()
        try {
            return executor.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** A disc lent to the rip engine; see [lease]. */
    private inner class Lease(
        private val owner: OpenDrive,
        override val info: DiscInfo,
        private val reader: DriveReader,
    ) : DriveDisc {

        /** The drive went away under the loan (unplugged, or given up on). */
        @Volatile
        var gone = false

        @Volatile
        private var closed = false

        override val problem: DriveProblem?
            get() = if (gone) DriveProblem.DISCONNECTED else reader.problem

        override fun read(lba: Long, count: Int, buffer: ByteArray): Int {
            if (gone || closed) return DriveReader.FAILED
            return onDriveBlocking { if (gone) DriveReader.FAILED else reader.read(lba, count, buffer) }
        }

        override fun unlock() = onDriveBlocking {
            if (gone) throw UsbTransferException(TransferFailure.DISCONNECTED, "The drive is gone")
            reader.unlock()
        }

        override fun preventRemoval(prevent: Boolean) = onDriveBlocking {
            if (gone) return@onDriveBlocking
            try {
                owner.drive.setRemovalPrevented(prevent)
            } catch (e: IOException) {
                Log.w(TAG, "Locking the tray failed", e)
            }
        }

        override fun eject(): Boolean = onDriveBlocking {
            if (gone) return@onDriveBlocking false
            try {
                owner.monitor.eject()
                true
            } catch (e: IOException) {
                Log.w(TAG, "Eject failed", e)
                false
            }
        }

        override fun close() = onDriveBlocking {
            if (closed) return@onDriveBlocking
            closed = true
            if (lease !== this) return@onDriveBlocking
            lease = null
            // The reads took the unit attention of a disc change (or reset) the monitor would have
            // seen: it reads the drive afresh. Otherwise the disc stays the one it knows.
            if (reader.problem != null || reader.attentions > 0) owner.monitor.invalidate()
            publish(owner)
            poll()
        }
    }

    /**
     * Looks at the attached mass-storage devices, likely drives first, and connects to the first
     * one DiscRip may use. Devices found not to be drives are passed over.
     */
    private fun refresh() {
        open?.let { current ->
            if (usbManager.deviceList.containsKey(current.deviceName)) return
            close()
        }
        val devices = usbManager.deviceList.values
            .mapNotNull { device -> MassStorageInterface.find(device)?.let { device to it } }
            .sortedByDescending { (device, storage) -> storage.looksOptical || OPTICAL_NAME.containsMatchIn(device.productName.orEmpty()) }
            .map { it.first }
        if (devices.isEmpty()) {
            statusState.value = DriveStatus.NoDrive
            return
        }
        for (device in devices) {
            if (device.deviceName in notOptical) continue
            if (!usbManager.hasPermission(device)) {
                val candidate = UsbCandidate.of(device)
                statusState.value = if (device.deviceName in denied) DriveStatus.PermissionDenied(candidate) else DriveStatus.NeedsPermission(candidate)
                return
            }
            connect(device, userAsked = false)
            if (device.deviceName !in notOptical) return
        }
        statusState.value = DriveStatus.NotOptical(UsbCandidate.of(devices.first()), null)
    }

    private fun requestPermission(device: UsbDevice) {
        // FLAG_MUTABLE: the system adds the device and the answer as extras. The intent names
        // this package, as Android 14 requires for mutable pending intents.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        statusState.value = DriveStatus.AwaitingPermission(UsbCandidate.of(device))
        usbManager.requestPermission(device, pending)
    }

    private fun permissionAnswered(device: UsbDevice, granted: Boolean) {
        if (granted) {
            connect(device, userAsked = true)
        } else {
            denied += device.deviceName
            statusState.value = DriveStatus.PermissionDenied(UsbCandidate.of(device))
        }
    }

    private fun connect(device: UsbDevice, userAsked: Boolean) {
        if (open?.deviceName == device.deviceName) return publish(open!!)
        close()
        val candidate = UsbCandidate.of(device)
        val serial = runCatching { device.serialNumber }.getOrNull()
        if (userAsked) {
            notOptical -= device.deviceName
            nonOptical.forget(device, serial)
        } else if (device.deviceName in notOptical || nonOptical.contains(device, serial)) {
            notOptical += device.deviceName
            statusState.value = DriveStatus.NotOptical(candidate, null)
            return
        }
        val storage = MassStorageInterface.find(device) ?: return refresh()
        statusState.value = DriveStatus.Connecting(candidate)

        val connection = try {
            usbManager.openDevice(device)
        } catch (e: SecurityException) {
            // Permission was withdrawn between the check and the call.
            statusState.value = DriveStatus.NeedsPermission(candidate)
            return
        }
        if (connection == null) {
            statusState.value = DriveStatus.Failed(candidate, DriveFailure.OPEN_FAILED, null)
            return
        }
        if (!connection.claimInterface(storage.usbInterface, true)) {
            connection.close()
            statusState.value = DriveStatus.Failed(candidate, DriveFailure.CLAIM_FAILED, null)
            return
        }
        // A UAS driver may have left the interface in its UAS setting; Bulk-Only is setting 0.
        if (storage.hasAlternateSettings) connection.setInterface(storage.usbInterface)
        val attached = { usbManager.deviceList.containsKey(device.deviceName) }
        val transport: MassStorageTransport = LibusbTransport.open(connection, storage, attached)
            ?: AndroidBulkTransport(connection, storage, attached)

        val found = try {
            findOpticalUnit(transport)
        } catch (e: IOException) {
            Log.w(TAG, "No answer from ${candidate.displayName}", e)
            transport.close()
            statusState.value = DriveStatus.Failed(candidate, DriveFailure.NO_ANSWER, e.message)
            return
        }
        if (found.drive == null) {
            transport.close()
            notOptical += device.deviceName
            nonOptical.remember(device, serial)
            statusState.value = DriveStatus.NotOptical(candidate, found.inquiry)
            return
        }
        val info = DriveInfo(
            device = candidate,
            inquiry = found.inquiry!!,
            transport = transport.kind,
            libusbVersion = if (transport.kind == TransportKind.LIBUSB) LibusbTransport.version else null,
        )
        // A tray an earlier run left locked (it ended mid-rip) opens again.
        runCatching { found.drive.setRemovalPrevented(false) }
        val drive = OpenDrive(device.deviceName, transport, found.drive, DriveMonitor(found.drive), info)
        open = drive
        publish(drive)
        poll()
    }

    private class LogicalUnitScan(val drive: OpticalDrive?, val inquiry: InquiryData?)

    /** The first logical unit that is a CD/DVD drive; card-reader combos can have several. */
    private fun findOpticalUnit(transport: MassStorageTransport): LogicalUnitScan {
        val lastLun = transport.maxLun()
        var first: InquiryData? = null
        for (lun in 0..lastLun) {
            val drive = OpticalDrive(BulkOnlyTransport(transport, lun))
            val inquiry = drive.inquiry()
            if (inquiry.isOpticalDrive) return LogicalUnitScan(drive, inquiry)
            first = first ?: inquiry
        }
        return LogicalUnitScan(null, first)
    }

    private fun poll() {
        val drive = open ?: return
        if (lease != null) return
        try {
            drive.monitor.poll()
            publish(drive)
        } catch (e: DriveUnresponsiveException) {
            Log.w(TAG, "Drive stopped responding", e)
            close()
            statusState.value = DriveStatus.Failed(drive.info.device, DriveFailure.UNRESPONSIVE, e.message)
        } catch (e: UsbTransferException) {
            if (e.failure != TransferFailure.DISCONNECTED) Log.w(TAG, "Polling failed", e)
            close()
            refresh()
        }
    }

    private fun publish(drive: OpenDrive) {
        statusState.value = DriveStatus.Connected(drive.info, drive.monitor.state, inUse = lease != null)
    }

    private fun detached(deviceName: String?) {
        if (deviceName != null && deviceName == open?.deviceName) close()
        denied -= deviceName.orEmpty()
        notOptical -= deviceName.orEmpty()
        refresh()
    }

    private fun close() {
        val drive = open ?: return
        open = null
        lease?.gone = true
        lease = null
        runCatching { drive.transport.close() }
    }

    companion object {
        private const val TAG = "DiscRip"
        private const val ACTION_USB_PERMISSION = "be.discrip.USB_PERMISSION"
        private const val POLL_INTERVAL_MILLIS = 1_000L

        /** Only orders candidates; INQUIRY decides what is a drive. */
        private val OPTICAL_NAME = Regex("\\b(DVD|CD|BD|BLU-?RAY|OPTICAL|ODD|SUPERMULTI)", RegexOption.IGNORE_CASE)
    }
}
