package be.discrip.core.scsi

import be.discrip.core.usb.BotProtocolException
import be.discrip.core.usb.BulkOnlyTransport
import be.discrip.core.usb.CommandStatus
import be.discrip.core.usb.DataDirection
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException
import java.io.IOException

/** A command ended in CHECK CONDITION; [sense] says why. */
class ScsiCommandException(val operation: Int, val sense: SenseData) :
    IOException("SCSI command 0x%02X failed with sense %s".format(operation, sense))

/** A read kept failing after all retries. */
class ReadFailedException(val lba: Long, val blocks: Int, cause: IOException) :
    IOException("Blocks $lba..${lba + blocks - 1} unreadable: ${cause.message}", cause)

/**
 * A DVD-ROM drive spoken to over SCSI MMC. All calls block; callers keep them on one thread (the
 * transport serializes commands anyway).
 */
class OpticalDrive(private val bot: BulkOnlyTransport) : AutoCloseable {

    /** False once the drive rejected GET EVENT STATUS NOTIFICATION; polling then uses TEST UNIT READY. */
    var supportsMediaEvents: Boolean = true
        private set

    fun inquiry(): InquiryData {
        val buffer = ByteArray(36)
        val received = send(ScsiCommands.inquiry(buffer.size), DataDirection.IN, buffer, length = buffer.size)
        return InquiryData.parse(buffer, received) ?: throw IOException("Malformed INQUIRY data")
    }

    /** Null when the unit is ready, otherwise the reason it is not. */
    fun testUnitReady(): SenseData? =
        try {
            send(ScsiCommands.testUnitReady())
            null
        } catch (e: ScsiCommandException) {
            e.sense
        }

    fun requestSense(): SenseData {
        val buffer = ByteArray(18)
        val result = bot.execute(
            ScsiCommands.requestSense(buffer.size),
            DataDirection.IN,
            buffer,
            0,
            buffer.size,
            SHORT_TIMEOUT_MILLIS,
        )
        if (result.status != CommandStatus.PASSED) return SenseData.NONE
        return SenseData.parse(buffer, result.transferred) ?: SenseData.NONE
    }

    /**
     * Media presence and events, or null when the drive does not support polled GET EVENT STATUS
     * NOTIFICATION (then remembered in [supportsMediaEvents]).
     */
    fun mediaEventStatus(): MediaEventStatus? {
        if (!supportsMediaEvents) return null
        val buffer = ByteArray(8)
        return try {
            val received = send(
                ScsiCommands.getEventStatusNotification(allocationLength = buffer.size),
                DataDirection.IN,
                buffer,
                length = buffer.size,
            )
            MediaEventStatus.parse(buffer, received)
        } catch (e: ScsiCommandException) {
            if (e.sense.senseKey == SenseData.KEY_ILLEGAL_REQUEST) {
                supportsMediaEvents = false
                null
            } else {
                throw e
            }
        }
    }

    fun readCapacity(): Capacity {
        val buffer = ByteArray(8)
        val received = send(ScsiCommands.readCapacity10(), DataDirection.IN, buffer, length = buffer.size, timeoutMillis = LONG_TIMEOUT_MILLIS)
        return Capacity.parse(buffer, received) ?: throw IOException("Malformed READ CAPACITY data")
    }

    /** The current MMC profile, or null when the drive does not implement GET CONFIGURATION. */
    fun currentProfile(): DiscProfile? {
        val buffer = ByteArray(8)
        return try {
            val received = send(ScsiCommands.getConfiguration(), DataDirection.IN, buffer, length = buffer.size)
            DiscProfile.parseHeader(buffer, received)
        } catch (e: ScsiCommandException) {
            if (e.sense.senseKey == SenseData.KEY_ILLEGAL_REQUEST) null else throw e
        }
    }

    /** Copy protection and region mask of the disc, or null when the drive or medium cannot say. */
    fun copyrightInfo(): CopyrightInfo? = optionalStructure(8) { buffer ->
        send(ScsiCommands.readDvdStructure(ScsiCommands.DVD_STRUCTURE_COPYRIGHT, allocationLength = buffer.size), DataDirection.IN, buffer, length = buffer.size)
            .let { CopyrightInfo.parse(buffer, it) }
    }

    /** The drive's region setting, or null when it does not report one (RPC-I drives often do not). */
    fun rpcState(): RpcState? = optionalStructure(8) { buffer ->
        send(ScsiCommands.reportKey(ScsiCommands.KEY_FORMAT_RPC_STATE, allocationLength = buffer.size), DataDirection.IN, buffer, length = buffer.size)
            .let { RpcState.parse(buffer, it) }
    }

    /**
     * Reads [blocks] 2048-byte blocks starting at [lba], [MAX_BLOCKS_PER_READ] per command. Each
     * command is retried [retries] times on transient errors; conditions that cannot improve
     * (region mismatch, no medium, out of range) fail at once.
     */
    fun read(lba: Long, blocks: Int, buffer: ByteArray, offset: Int = 0, retries: Int = MAX_READ_RETRIES) {
        require(blocks >= 0) { "Negative block count" }
        require(retries >= 0) { "Negative retry count" }
        require(offset >= 0 && offset.toLong() + blocks.toLong() * BLOCK_SIZE <= buffer.size) { "Buffer too small" }
        var done = 0
        while (done < blocks) {
            val count = minOf(MAX_BLOCKS_PER_READ, blocks - done)
            readChunk(lba + done, count, buffer, offset + done * BLOCK_SIZE, retries)
            done += count
        }
    }

    // CSS authentication (MMC-6, 4.10.4); CssAuthentication drives the sequence.

    /** Asks for an authentication grant ID; fails while all four are in use. */
    fun reportAgid(): Int {
        val buffer = ByteArray(8)
        send(ScsiCommands.reportKey(ScsiCommands.KEY_FORMAT_AGID, buffer.size), DataDirection.IN, buffer, length = buffer.size)
        return (buffer[7].toInt() and 0xFF) ushr 6
    }

    fun invalidateAgid(agid: Int) {
        send(ScsiCommands.reportKey(ScsiCommands.KEY_FORMAT_INVALIDATE_AGID, 0, agid))
    }

    /** Sends the host's 10-byte challenge, in the byte order the drive reads it. */
    fun sendChallenge(agid: Int, challenge: ByteArray) {
        val parameters = ByteArray(16)
        parameters[1] = 0x0E
        challenge.copyInto(parameters, 4, 0, 10)
        send(ScsiCommands.sendKey(ScsiCommands.KEY_FORMAT_CHALLENGE, parameters.size, agid), DataDirection.OUT, parameters, length = parameters.size)
    }

    /** The drive's answer to the host's challenge: 5 bytes. */
    fun reportKey1(agid: Int): ByteArray {
        val buffer = ByteArray(12)
        send(ScsiCommands.reportKey(ScsiCommands.KEY_FORMAT_KEY1, buffer.size, agid), DataDirection.IN, buffer, length = buffer.size)
        return buffer.copyOfRange(4, 9)
    }

    /** The drive's own 10-byte challenge. */
    fun reportChallenge(agid: Int): ByteArray {
        val buffer = ByteArray(16)
        send(ScsiCommands.reportKey(ScsiCommands.KEY_FORMAT_CHALLENGE, buffer.size, agid), DataDirection.IN, buffer, length = buffer.size)
        return buffer.copyOfRange(4, 14)
    }

    /** Sends the host's answer to the drive's challenge: 5 bytes. */
    fun sendKey2(agid: Int, key: ByteArray) {
        val parameters = ByteArray(12)
        parameters[1] = 0x0A
        key.copyInto(parameters, 4, 0, 5)
        send(ScsiCommands.sendKey(ScsiCommands.KEY_FORMAT_KEY2, parameters.size, agid), DataDirection.OUT, parameters, length = parameters.size)
    }

    /** The disc key block (2048 bytes) under the bus key of an authenticated [agid]. */
    fun readDiscKey(agid: Int): ByteArray {
        val buffer = ByteArray(DISC_KEY_SIZE + 4)
        send(
            ScsiCommands.readDvdStructure(ScsiCommands.DVD_STRUCTURE_DISC_KEY, allocationLength = buffer.size, agid = agid),
            DataDirection.IN,
            buffer,
            length = buffer.size,
        )
        return buffer.copyOfRange(4, buffer.size)
    }

    /** The authentication success flag: set, the drive hands out the disc's scrambled sectors. */
    fun authenticationSucceeded(): Boolean {
        val buffer = ByteArray(8)
        send(ScsiCommands.reportKey(ScsiCommands.KEY_FORMAT_ASF, buffer.size), DataDirection.IN, buffer, length = buffer.size)
        return buffer[7].toInt() and 0x01 != 0
    }

    fun setRemovalPrevented(prevent: Boolean) {
        send(ScsiCommands.preventAllowMediumRemoval(prevent))
    }

    /** Unlocks the tray and ejects the disc. */
    fun eject() {
        setRemovalPrevented(false)
        send(ScsiCommands.startStopUnit(start = false, loadEject = true), timeoutMillis = LONG_TIMEOUT_MILLIS)
    }

    /** Closes the tray. */
    fun load() {
        send(ScsiCommands.startStopUnit(start = true, loadEject = true), timeoutMillis = LONG_TIMEOUT_MILLIS)
    }

    override fun close() = bot.close()

    private fun readChunk(lba: Long, count: Int, buffer: ByteArray, offset: Int, maxRetries: Int) {
        val bytes = count * BLOCK_SIZE
        var retries = 0
        while (true) {
            val failure: IOException = try {
                val received = send(ScsiCommands.read10(lba, count), DataDirection.IN, buffer, offset, bytes, LONG_TIMEOUT_MILLIS)
                if (received == bytes) return
                IOException("Short read: $received of $bytes bytes")
            } catch (e: ScsiCommandException) {
                if (!e.sense.isRetryable) throw e
                e
            } catch (e: UsbTransferException) {
                if (e.failure == TransferFailure.DISCONNECTED) throw e
                e
            } catch (e: BotProtocolException) {
                e
            }
            if (++retries > maxRetries) throw ReadFailedException(lba, count, failure)
        }
    }

    /**
     * Runs an informational command whose failure only means "unknown": CHECK CONDITION gives
     * null, except for conditions the caller has to see (no medium, a changed medium).
     */
    private inline fun <T> optionalStructure(size: Int, block: (ByteArray) -> T?): T? =
        try {
            block(ByteArray(size))
        } catch (e: ScsiCommandException) {
            when (e.sense.category) {
                SenseCategory.NO_MEDIUM, SenseCategory.MEDIUM_CHANGED, SenseCategory.RESET -> throw e
                else -> null
            }
        }

    /** Sends a command; returns the bytes received. CHECK CONDITION becomes [ScsiCommandException]. */
    private fun send(
        cdb: ByteArray,
        direction: DataDirection = DataDirection.NONE,
        buffer: ByteArray? = null,
        offset: Int = 0,
        length: Int = 0,
        timeoutMillis: Int = SHORT_TIMEOUT_MILLIS,
    ): Int {
        val result = bot.execute(cdb, direction, buffer, offset, length, timeoutMillis)
        if (result.status == CommandStatus.FAILED) {
            throw ScsiCommandException(cdb[0].toInt() and 0xFF, requestSense())
        }
        return result.transferred
    }

    companion object {
        const val BLOCK_SIZE = 2048
        const val DISC_KEY_SIZE = 2048

        /** 32 blocks = 64 KiB per READ(10): large enough for throughput, small enough to retry. */
        const val MAX_BLOCKS_PER_READ = 32

        /** Retries per block range before giving up on it. */
        const val MAX_READ_RETRIES = 3

        const val SHORT_TIMEOUT_MILLIS = 5_000

        /** Reads and eject include spin-up, which takes several seconds on a cold drive. */
        const val LONG_TIMEOUT_MILLIS = 30_000
    }
}
