package be.discrip.usb

import android.hardware.usb.UsbDeviceConnection
import be.discrip.core.usb.BulkTransport
import be.discrip.core.usb.Endpoint
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException

/**
 * Bulk-Only Transport pipes through libusb, on the descriptor of an open [UsbDeviceConnection]
 * whose interface is already claimed. Preferred over [AndroidBulkTransport]: libusb tells a stall
 * from a timeout from an unplugged device, keeps the bytes that arrived before a failure, and
 * clears halts with the USBDEVFS_CLEAR_HALT ioctl, which also resets the host's data toggle.
 * Owns the connection: closing the transport releases the interface and closes it.
 */
class LibusbTransport private constructor(
    private var session: Long,
    private val connection: UsbDeviceConnection,
    private val storage: MassStorageInterface,
    private val isAttached: () -> Boolean,
) : MassStorageTransport {

    override val kind = TransportKind.LIBUSB
    private val transferred = IntArray(1)

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
        transfer(storage.outEndpoint.address, buffer, offset, length, timeoutMillis)

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
        transfer(storage.inEndpoint.address, buffer, offset, length, timeoutMillis)

    override fun clearHalt(endpoint: Endpoint) {
        val address = if (endpoint == Endpoint.IN) storage.inEndpoint.address else storage.outEndpoint.address
        val result = NativeUsb.clearHalt(openSession(), address)
        if (result != 0) throw failure(result, "Clearing the halt on endpoint 0x%02X".format(address))
    }

    override fun massStorageReset() {
        val result = NativeUsb.controlTransfer(
            openSession(),
            MassStorageRequests.RESET_REQUEST_TYPE,
            MassStorageRequests.RESET_REQUEST,
            0,
            storage.number,
            null,
            0,
            MassStorageRequests.CONTROL_TIMEOUT_MILLIS,
        )
        if (result < 0) throw failure(result, "Mass storage reset")
    }

    override fun maxLun(): Int {
        val answer = ByteArray(1)
        val result = NativeUsb.controlTransfer(
            openSession(),
            MassStorageRequests.MAX_LUN_REQUEST_TYPE,
            MassStorageRequests.MAX_LUN_REQUEST,
            0,
            storage.number,
            answer,
            1,
            MassStorageRequests.CONTROL_TIMEOUT_MILLIS,
        )
        // Single-LUN devices may stall this request (section 3.2).
        return if (result == 1) answer[0].toInt() and 0x0F else 0
    }

    override fun close() {
        if (session != 0L) {
            NativeUsb.close(session)
            session = 0
        }
        connection.releaseInterface(storage.usbInterface)
        connection.close()
    }

    private fun openSession(): Long {
        if (session == 0L) throw UsbTransferException(TransferFailure.DISCONNECTED, "Transport closed")
        return session
    }

    private fun transfer(endpoint: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Bad buffer range" }
        val result = NativeUsb.bulkTransfer(openSession(), endpoint, buffer, offset, length, timeoutMillis, transferred)
        if (result == 0) return transferred[0]
        throw failure(result, "Bulk transfer on endpoint 0x%02X".format(endpoint), transferred[0])
    }

    private fun failure(code: Int, what: String, moved: Int = 0): UsbTransferException {
        val kind = when (code) {
            NativeUsb.ERROR_TIMEOUT -> TransferFailure.TIMEOUT
            NativeUsb.ERROR_NO_DEVICE -> TransferFailure.DISCONNECTED
            NativeUsb.ERROR_IO, NativeUsb.ERROR_OTHER -> if (isAttached()) TransferFailure.STALL_OR_ERROR else TransferFailure.DISCONNECTED
            else -> TransferFailure.STALL_OR_ERROR
        }
        return UsbTransferException(kind, "$what failed: ${NativeUsb.errorName(code)}", moved)
    }

    companion object {
        /** Wraps [connection] in libusb, or null when the native library or libusb cannot. */
        fun open(connection: UsbDeviceConnection, storage: MassStorageInterface, isAttached: () -> Boolean): LibusbTransport? {
            if (!NativeUsb.isAvailable) return null
            val error = IntArray(1)
            val session = NativeUsb.open(connection.fileDescriptor, error)
            if (session == 0L) return null
            return LibusbTransport(session, connection, storage, isAttached)
        }

        val version: String? get() = if (NativeUsb.isAvailable) NativeUsb.version() else null
    }
}
