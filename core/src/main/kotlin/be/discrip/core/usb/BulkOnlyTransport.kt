package be.discrip.core.usb

import java.io.IOException

/** The device broke the Bulk-Only protocol (bad CSW, wrong tag, phase error); a reset was issued. */
class BotProtocolException(message: String) : IOException(message)

/**
 * USB Mass Storage Bulk-Only Transport: wraps each SCSI command in a CBW, runs the data phase and
 * reads the CSW, with the error recovery of section 5.3 (clear a stalled pipe, reset recovery on
 * protocol errors). Commands are serialized; the drive only handles one at a time.
 */
class BulkOnlyTransport(
    private val io: BulkTransport,
    private val lun: Int = 0,
) : AutoCloseable {

    data class Result(
        val status: CommandStatus,
        /** Bytes actually moved in the data phase. */
        val transferred: Int,
        /** dCSWDataResidue: what the device did not process. */
        val residue: Int,
    )

    private var lastTag = 0

    @Synchronized
    fun execute(
        command: ByteArray,
        direction: DataDirection,
        buffer: ByteArray? = null,
        offset: Int = 0,
        length: Int = 0,
        timeoutMillis: Int,
    ): Result {
        require(direction == DataDirection.NONE || buffer != null) { "Data phase needs a buffer" }
        val expected = if (direction == DataDirection.NONE) 0 else length
        if (buffer != null) require(offset >= 0 && offset + expected <= buffer.size) { "Buffer too small" }

        val tag = ++lastTag
        val cbw = CommandBlockWrapper(tag, expected, if (expected == 0) DataDirection.NONE else direction, lun, command)
        try {
            sendFully(cbw.encode(), timeoutMillis)
        } catch (e: UsbTransferException) {
            if (e.failure != TransferFailure.DISCONNECTED) resetRecovery()
            throw e
        }

        var transferred = 0
        if (expected > 0) {
            try {
                transferred = when (direction) {
                    DataDirection.IN -> io.bulkIn(buffer!!, offset, expected, timeoutMillis)
                    DataDirection.OUT -> sendFully(buffer!!, timeoutMillis, offset, expected)
                    DataDirection.NONE -> 0
                }
            } catch (e: UsbTransferException) {
                if (e.failure == TransferFailure.DISCONNECTED) throw e
                // A device that fails a command, or has less data than asked for, stalls the data
                // pipe (section 6.7.2); keep what arrived, clear the halt and fetch the status.
                transferred = e.transferred
                io.clearHalt(if (direction == DataDirection.IN) Endpoint.IN else Endpoint.OUT)
            }
        }

        val csw = receiveStatus(tag, timeoutMillis)
        if (csw.status == CommandStatus.PHASE_ERROR) {
            resetRecovery()
            throw BotProtocolException("Phase error reported for command 0x%02X".format(command[0].toInt() and 0xFF))
        }
        return Result(csw.status, transferred, csw.dataResidue.coerceIn(0, expected))
    }

    /** Bulk-Only Mass Storage Reset followed by clearing both halts (section 5.3.4). */
    fun resetRecovery() {
        runCatching { io.massStorageReset() }
        runCatching { io.clearHalt(Endpoint.IN) }
        runCatching { io.clearHalt(Endpoint.OUT) }
    }

    override fun close() = io.close()

    private fun receiveStatus(tag: Int, timeoutMillis: Int): CommandStatusWrapper {
        val bytes = ByteArray(CommandStatusWrapper.SIZE)
        var stalled = false
        while (true) {
            val received = try {
                io.bulkIn(bytes, 0, bytes.size, timeoutMillis)
            } catch (e: UsbTransferException) {
                if (e.failure == TransferFailure.DISCONNECTED) throw e
                if (stalled) {
                    resetRecovery()
                    throw e
                }
                // Section 6.7.2: on a stall while reading the CSW, clear the halt and try once more.
                stalled = true
                io.clearHalt(Endpoint.IN)
                continue
            }
            val csw = CommandStatusWrapper.decode(bytes, received)
            if (csw == null || csw.tag != tag) {
                resetRecovery()
                throw BotProtocolException("Invalid command status wrapper")
            }
            return csw
        }
    }

    private fun sendFully(bytes: ByteArray, timeoutMillis: Int, offset: Int = 0, length: Int = bytes.size): Int {
        var sent = 0
        while (sent < length) {
            val n = io.bulkOut(bytes, offset + sent, length - sent, timeoutMillis)
            if (n <= 0) throw UsbTransferException(TransferFailure.STALL_OR_ERROR, "Bulk OUT wrote nothing")
            sent += n
        }
        return sent
    }
}
