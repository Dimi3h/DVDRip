package be.discrip.core.testing

import be.discrip.core.disc.BlockDevice
import be.discrip.core.scsi.ScsiCommands
import be.discrip.core.usb.BulkTransport
import be.discrip.core.usb.CommandBlockWrapper
import be.discrip.core.usb.CommandStatus
import be.discrip.core.usb.CommandStatusWrapper
import be.discrip.core.usb.DataDirection
import be.discrip.core.usb.Endpoint
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException

/**
 * A USB DVD drive at the bulk-endpoint level: Bulk-Only Transport framing around an MMC command
 * set, with a tray, spin-up, unit attentions, media events, regional playback control and fault
 * injection. The disc is any [BlockDevice], typically an image from [TestImages].
 */
class SimulatedOpticalDrive : BulkTransport {

    data class Sense(val key: Int, val asc: Int, val ascq: Int)

    /** A block that fails with [sense] the next [times] reads that touch it. */
    class ReadFault(val sense: Sense, var times: Int)

    // Identity and capabilities

    var deviceType = 0x05
    var vendor = "DISCRIP"
    var product = "SIMULATED DVD"
    var revision = "1.00"
    var profile = 0x0010
    var supportsMediaEvents = true
    var supportsConfiguration = true
    var supportsDvdStructure = true
    var supportsReportKey = true

    /** TEST UNIT READY answers "becoming ready" this many times after a disc goes in. */
    var spinUpPolls = 2

    // Copy protection and region

    var copyProtection = 0
    var discRegionMask = 0x00
    var rpcScheme = 1
    var rpcType = 1
    var driveRegionMask = 0xFD
    var userChangesLeft = 4
    var vendorResetsLeft = 4

    // Fault injection

    val readFaults = mutableMapOf<Long, ReadFault>()

    /** A failing command with a data-in phase stalls the bulk-in pipe (most drives do). */
    var stallOnFailure = true

    /** Data shorter than the host asked for is followed by a stall instead of a short packet. */
    var stallAfterShortData = false
    var disconnected = false

    /** The next N bulk transfers time out. */
    var timeouts = 0
    var corruptNextStatus = false
    var wrongTagNextStatus = false
    var phaseErrorNextCommand = false

    /** Reading the next status stalls this many times. */
    var statusStalls = 0

    // What the host did

    val commands = mutableListOf<ByteArray>()
    val tags = mutableListOf<Int>()
    var resets = 0
        private set
    val clearedHalts = mutableListOf<Endpoint>()
    var closed = false
        private set

    fun count(opcode: Int) = commands.count { (it[0].toInt() and 0xFF) == opcode }

    // Medium and tray

    var disc: BlockDevice? = null
        private set
    var trayOpen = false
        private set
    var locked = false
        private set
    private var spinUpLeft = 0
    private var unitAttention: Sense? = Sense(0x06, 0x29, 0x00)
    private var mediaEvent = 0
    private var lastSense: Sense? = null

    /** Puts a disc in and closes the tray, as a user would. */
    fun insert(image: BlockDevice) {
        disc = image
        closeTray()
    }

    /** Opens the tray and takes the disc out. */
    fun removeDisc() {
        openTray()
        disc = null
    }

    fun openTray() {
        if (!trayOpen && disc != null) mediaEvent = EVENT_MEDIA_REMOVAL
        trayOpen = true
    }

    fun closeTray() {
        trayOpen = false
        if (disc != null) {
            mediaEvent = EVENT_NEW_MEDIA
            spinUpLeft = spinUpPolls
            unitAttention = Sense(0x06, 0x28, 0x00)
        }
    }

    // Bulk-Only Transport

    private enum class Phase { COMMAND, DATA_IN, STATUS }

    private var phase = Phase.COMMAND
    private var inHalted = false
    private var dataIn = ByteArray(0)
    private var status = ByteArray(0)

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        failTransferIfInjected()
        check(phase == Phase.COMMAND) { "Host sent data while the drive expected $phase" }
        val cbw = CommandBlockWrapper.decode(buffer.copyOfRange(offset, offset + length), length)
            ?: throw UsbTransferException(TransferFailure.STALL_OR_ERROR, "Invalid CBW")
        commands += cbw.commandBlock
        tags += cbw.tag
        handle(cbw)
        return length
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        failTransferIfInjected()
        if (inHalted) throw UsbTransferException(TransferFailure.STALL_OR_ERROR, "Bulk-in endpoint halted")
        return when (phase) {
            Phase.DATA_IN -> {
                val n = minOf(length, dataIn.size)
                dataIn.copyInto(buffer, offset, 0, n)
                phase = Phase.STATUS
                if (stallAfterShortData && n < length) {
                    inHalted = true
                    throw UsbTransferException(TransferFailure.STALL_OR_ERROR, "Stalled after $n bytes", transferred = n)
                }
                n
            }
            Phase.STATUS -> {
                if (statusStalls > 0) {
                    statusStalls--
                    inHalted = true
                    throw UsbTransferException(TransferFailure.STALL_OR_ERROR, "Stalled before the status")
                }
                val n = minOf(length, status.size)
                status.copyInto(buffer, offset, 0, n)
                phase = Phase.COMMAND
                n
            }
            Phase.COMMAND -> throw UsbTransferException(TransferFailure.TIMEOUT, "Nothing to send")
        }
    }

    override fun clearHalt(endpoint: Endpoint) {
        if (disconnected) throw UsbTransferException(TransferFailure.DISCONNECTED, "Gone")
        clearedHalts += endpoint
        if (endpoint == Endpoint.IN) inHalted = false
    }

    override fun massStorageReset() {
        if (disconnected) throw UsbTransferException(TransferFailure.DISCONNECTED, "Gone")
        resets++
        phase = Phase.COMMAND
    }

    override fun close() {
        closed = true
    }

    private fun failTransferIfInjected() {
        if (disconnected) throw UsbTransferException(TransferFailure.DISCONNECTED, "Gone")
        if (timeouts > 0) {
            timeouts--
            throw UsbTransferException(TransferFailure.TIMEOUT, "Timed out")
        }
    }

    private fun handle(cbw: CommandBlockWrapper) {
        val expected = cbw.dataTransferLength
        var commandStatus = CommandStatus.PASSED
        var data = ByteArray(0)

        if (phaseErrorNextCommand) {
            phaseErrorNextCommand = false
            commandStatus = CommandStatus.PHASE_ERROR
        } else {
            when (val outcome = execute(cbw.commandBlock)) {
                is Outcome.Data -> data = outcome.bytes.copyOf(minOf(outcome.bytes.size, expected))
                is Outcome.Failed -> {
                    lastSense = outcome.sense
                    commandStatus = CommandStatus.FAILED
                }
            }
        }

        var tag = cbw.tag
        if (wrongTagNextStatus) {
            wrongTagNextStatus = false
            tag++
        }
        val residue = if (commandStatus == CommandStatus.PASSED) expected - data.size else expected
        status = CommandStatusWrapper(tag, residue, commandStatus).encode()
        if (corruptNextStatus) {
            corruptNextStatus = false
            status[0] = 0
        }

        if (cbw.direction == DataDirection.IN && expected > 0) {
            phase = Phase.DATA_IN
            dataIn = data
            if (commandStatus != CommandStatus.PASSED && stallOnFailure) {
                inHalted = true
                phase = Phase.STATUS
            }
        } else {
            phase = Phase.STATUS
        }
    }

    // MMC

    private sealed interface Outcome {
        class Data(val bytes: ByteArray) : Outcome
        class Failed(val sense: Sense) : Outcome
    }

    private fun ok(bytes: ByteArray = ByteArray(0)): Outcome = Outcome.Data(bytes)

    private fun fail(key: Int, asc: Int, ascq: Int = 0): Outcome = Outcome.Failed(Sense(key, asc, ascq))

    private fun execute(cdb: ByteArray): Outcome {
        val opcode = cdb[0].toInt() and 0xFF
        if (opcode !in NO_UNIT_ATTENTION) {
            unitAttention?.let {
                unitAttention = null
                return Outcome.Failed(it)
            }
        }
        return when (opcode) {
            ScsiCommands.TEST_UNIT_READY -> mediumReady() ?: ok()
            ScsiCommands.REQUEST_SENSE -> requestSense()
            ScsiCommands.INQUIRY -> inquiry()
            ScsiCommands.READ_CAPACITY_10 -> mediumReady() ?: readCapacity()
            ScsiCommands.READ_10 -> mediumReady() ?: read(be32(cdb, 2), be16(cdb, 7).toLong())
            ScsiCommands.READ_12 -> mediumReady() ?: read(be32(cdb, 2), be32(cdb, 6))
            ScsiCommands.GET_CONFIGURATION -> configuration()
            ScsiCommands.GET_EVENT_STATUS_NOTIFICATION -> eventStatus(cdb)
            ScsiCommands.START_STOP_UNIT -> startStop(cdb)
            ScsiCommands.PREVENT_ALLOW_MEDIUM_REMOVAL -> {
                locked = cdb[4].toInt() and 0x01 != 0
                ok()
            }
            ScsiCommands.READ_DVD_STRUCTURE -> dvdStructure(cdb)
            ScsiCommands.REPORT_KEY -> reportKey(cdb)
            else -> fail(0x05, 0x20)
        }
    }

    private fun mediumReady(): Outcome? = when {
        trayOpen -> fail(0x02, 0x3A, 0x02)
        disc == null -> fail(0x02, 0x3A, 0x01)
        spinUpLeft > 0 -> {
            spinUpLeft--
            fail(0x02, 0x04, 0x01)
        }
        else -> null
    }

    private fun requestSense(): Outcome {
        val sense = lastSense ?: Sense(0, 0, 0)
        lastSense = null
        val bytes = ByteArray(18)
        bytes[0] = 0x70
        bytes[2] = sense.key.toByte()
        bytes[7] = 10
        bytes[12] = sense.asc.toByte()
        bytes[13] = sense.ascq.toByte()
        return ok(bytes)
    }

    private fun inquiry(): Outcome {
        val bytes = ByteArray(36)
        bytes[0] = deviceType.toByte()
        bytes[1] = 0x80.toByte()
        bytes[3] = 0x02
        bytes[4] = 31
        vendor.padEnd(8).toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        product.padEnd(16).toByteArray(Charsets.US_ASCII).copyInto(bytes, 16)
        revision.padEnd(4).toByteArray(Charsets.US_ASCII).copyInto(bytes, 32)
        return ok(bytes)
    }

    private fun readCapacity(): Outcome {
        val bytes = ByteArray(8)
        putBe32(bytes, 0, disc!!.blockCount - 1)
        putBe32(bytes, 4, BlockDevice.BLOCK_SIZE.toLong())
        return ok(bytes)
    }

    private fun read(lba: Long, blocks: Long): Outcome {
        val medium = disc!!
        if (lba + blocks > medium.blockCount) return fail(0x05, 0x21)
        for (block in lba until lba + blocks) {
            val fault = readFaults[block] ?: continue
            if (fault.times > 0) {
                fault.times--
                return Outcome.Failed(fault.sense)
            }
        }
        val bytes = ByteArray((blocks * BlockDevice.BLOCK_SIZE).toInt())
        medium.read(lba, blocks.toInt(), bytes)
        return ok(bytes)
    }

    private fun configuration(): Outcome {
        if (!supportsConfiguration) return fail(0x05, 0x20)
        val bytes = ByteArray(8)
        putBe32(bytes, 0, 4)
        val current = if (disc != null && !trayOpen) profile else 0
        bytes[6] = (current ushr 8).toByte()
        bytes[7] = current.toByte()
        return ok(bytes)
    }

    private fun eventStatus(cdb: ByteArray): Outcome {
        if (!supportsMediaEvents) return fail(0x05, 0x20)
        if (cdb[1].toInt() and 0x01 == 0) return fail(0x05, 0x24)
        val bytes = ByteArray(8)
        bytes[3] = 0x10
        if (cdb[4].toInt() and 0x10 == 0) {
            // No event available for the classes asked: header only.
            bytes[1] = 2
            bytes[2] = 0x80.toByte()
            return ok(bytes.copyOf(4))
        }
        bytes[1] = 6
        bytes[2] = 0x04
        bytes[4] = mediaEvent.toByte()
        var mediaStatus = 0
        if (trayOpen) mediaStatus = mediaStatus or 0x01
        if (disc != null && !trayOpen) mediaStatus = mediaStatus or 0x02
        bytes[5] = mediaStatus.toByte()
        mediaEvent = 0
        return ok(bytes)
    }

    private fun startStop(cdb: ByteArray): Outcome {
        val loadEject = cdb[4].toInt() and 0x02 != 0
        val start = cdb[4].toInt() and 0x01 != 0
        if (!loadEject) return ok()
        if (start) {
            if (trayOpen) closeTray()
            return ok()
        }
        if (locked) return fail(0x05, 0x53, 0x02)
        openTray()
        return ok()
    }

    private fun dvdStructure(cdb: ByteArray): Outcome {
        if (!supportsDvdStructure) return fail(0x05, 0x20)
        mediumReady()?.let { return it }
        if (cdb[7].toInt() != ScsiCommands.DVD_STRUCTURE_COPYRIGHT) return fail(0x05, 0x24)
        val bytes = ByteArray(8)
        bytes[1] = 6
        bytes[4] = copyProtection.toByte()
        bytes[5] = discRegionMask.toByte()
        return ok(bytes)
    }

    private fun reportKey(cdb: ByteArray): Outcome {
        if (!supportsReportKey) return fail(0x05, 0x20)
        if (cdb[10].toInt() and 0x3F != ScsiCommands.KEY_FORMAT_RPC_STATE) return fail(0x05, 0x24)
        val bytes = ByteArray(8)
        bytes[1] = 6
        bytes[4] = ((rpcType shl 6) or (vendorResetsLeft shl 3) or userChangesLeft).toByte()
        bytes[5] = driveRegionMask.toByte()
        bytes[6] = rpcScheme.toByte()
        return ok(bytes)
    }

    companion object {
        const val EVENT_NEW_MEDIA = 2
        const val EVENT_MEDIA_REMOVAL = 3

        /** Commands a pending unit attention does not get in the way of. */
        private val NO_UNIT_ATTENTION = setOf(
            ScsiCommands.INQUIRY,
            ScsiCommands.REQUEST_SENSE,
            ScsiCommands.GET_CONFIGURATION,
            ScsiCommands.GET_EVENT_STATUS_NOTIFICATION,
        )

        private fun be16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

        private fun be32(bytes: ByteArray, at: Int): Long =
            ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
                ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

        private fun putBe32(bytes: ByteArray, at: Int, value: Long) {
            for (i in 0 until 4) bytes[at + i] = (value ushr (24 - 8 * i)).toByte()
        }
    }
}
