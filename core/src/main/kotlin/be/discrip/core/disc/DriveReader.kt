package be.discrip.core.disc

import be.discrip.core.css.CssAuthentication
import be.discrip.core.css.CssAuthenticationException
import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.scsi.ReadFailedException
import be.discrip.core.scsi.ScsiCommandException
import be.discrip.core.scsi.SenseCategory
import be.discrip.core.usb.BotProtocolException
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException
import java.io.IOException

/** Why the disc in a drive cannot be read any further. */
enum class DriveProblem {
    /** The drive was unplugged, or stopped answering. */
    DISCONNECTED,

    /** The disc was taken out, or another one put in. */
    DISC_CHANGED,

    /** The drive's region is not one the disc allows (or was never set), so it refuses the scrambled sectors. */
    REGION_MISMATCH,

    /** The drive keeps refusing the scrambled sectors, also after CSS authentication. */
    CSS_FAILED,
}

/**
 * The disc in a drive as the rip engine reads it. Each read is tried once: the engine retries and
 * skips unreadable blocks itself, and on a scratched disc every attempt can cost the drive
 * seconds. What cannot get better ends reading for good and is kept in [problem]: the drive gone,
 * the disc taken out or swapped, a region the drive refuses. A drive that lost its CSS
 * authentication (after a bus reset, say) is authenticated again.
 *
 * Not thread-safe: call it from the one thread that talks to the drive.
 */
class DriveReader private constructor(
    private val drive: OpticalDrive,
    val blockCount: Long,
    /** The primary volume descriptor, which tells this disc from another after a medium change. */
    private val identity: ByteArray,
    private val sleep: (Long) -> Unit,
) {
    @Volatile
    var problem: DriveProblem? = null
        private set

    /** Unit attentions the reads took (a medium change, a reset), which a drive monitor then never sees. */
    var attentions = 0
        private set

    /**
     * Authenticates with the drive so it hands out the scrambled sectors of a CSS-protected disc.
     * Reads do this by themselves when the drive refuses one; doing it up front tells a region
     * mismatch before the rip starts.
     */
    fun unlock() {
        try {
            CssAuthentication.unlock(drive)
        } catch (e: CssAuthenticationException) {
            if (e.regionMismatch) problem = DriveProblem.REGION_MISMATCH
            throw e
        } catch (e: UsbTransferException) {
            if (e.failure == TransferFailure.DISCONNECTED) problem = DriveProblem.DISCONNECTED
            throw e
        }
    }

    /**
     * Reads up to [count] blocks at [lba] into [buffer]: the number read, fewer only at the end of
     * the disc, or [FAILED].
     */
    fun read(lba: Long, count: Int, buffer: ByteArray): Int {
        if (problem != null || count < 0 || lba < 0) return FAILED
        if (lba >= blockCount) return 0
        val blocks = minOf(count.toLong(), blockCount - lba).toInt()
        var authenticated = false
        var checkedDisc = false
        var waited = false
        while (true) {
            val failure = try {
                drive.read(lba, blocks, buffer, retries = 0)
                return blocks
            } catch (e: ReadFailedException) {
                e.cause as? IOException ?: e
            } catch (e: IOException) {
                e
            }
            val retry = when {
                failure is UsbTransferException || failure is BotProtocolException -> {
                    // Unplugged, or a transfer that timed out: over a bad block, or because the
                    // drive stopped answering? A simple command tells.
                    if (failure is UsbTransferException && failure.failure == TransferFailure.DISCONNECTED) {
                        problem = DriveProblem.DISCONNECTED
                    } else {
                        answers()
                    }
                    false
                }
                failure !is ScsiCommandException -> false
                else -> when (failure.sense.category) {
                    SenseCategory.NO_MEDIUM -> fail(DriveProblem.DISC_CHANGED)
                    SenseCategory.REGION_MISMATCH -> fail(DriveProblem.REGION_MISMATCH)
                    // The drive no longer counts the host as authenticated (or never did).
                    SenseCategory.COPY_PROTECTION ->
                        if (authenticated) fail(DriveProblem.CSS_FAILED) else reauthenticate().also { authenticated = true }
                    // A unit attention: the disc may have been swapped, or the drive was reset.
                    SenseCategory.MEDIUM_CHANGED, SenseCategory.RESET -> {
                        attentions++
                        !checkedDisc && sameDisc().also { checkedDisc = true }
                    }
                    SenseCategory.BECOMING_READY, SenseCategory.NOT_READY -> !waited && waitUntilReady().also { waited = true }
                    else -> false
                }
            }
            if (!retry) return FAILED
        }
    }

    private fun fail(found: DriveProblem): Boolean {
        problem = found
        return false
    }

    private fun reauthenticate(): Boolean = try {
        CssAuthentication.unlock(drive)
        true
    } catch (e: CssAuthenticationException) {
        fail(if (e.regionMismatch) DriveProblem.REGION_MISMATCH else DriveProblem.CSS_FAILED)
    } catch (e: IOException) {
        transportFailure(e)
    }

    /** After a unit attention: true when the disc in the drive is still the one being read. */
    private fun sameDisc(): Boolean {
        if (!waitUntilReady()) {
            // Not ready for long, or no disc at all: either way this disc is not coming back.
            if (problem == null) problem = DriveProblem.DISC_CHANGED
            return false
        }
        return try {
            val capacity = drive.readCapacity()
            val sector = ByteArray(OpticalDrive.BLOCK_SIZE)
            drive.read(IDENTITY_BLOCK, 1, sector, retries = 0)
            capacity.blockCount == blockCount && sector.contentEquals(identity) || fail(DriveProblem.DISC_CHANGED)
        } catch (e: IOException) {
            val cause = (e as? ReadFailedException)?.cause ?: e
            if (cause is UsbTransferException || cause is BotProtocolException) transportFailure(cause) else fail(DriveProblem.DISC_CHANGED)
        }
    }

    /** Waits for a spinning-up drive; false when it does not get ready, or has no disc. */
    private fun waitUntilReady(): Boolean {
        repeat(READY_POLLS) {
            val sense = try {
                drive.testUnitReady() ?: return true
            } catch (e: IOException) {
                return transportFailure(e)
            }
            when (sense.category) {
                SenseCategory.NO_MEDIUM -> return fail(DriveProblem.DISC_CHANGED)
                SenseCategory.BECOMING_READY, SenseCategory.NOT_READY -> sleep(READY_POLL_MILLIS)
                // Unit attentions report once; the next poll tells the state.
                SenseCategory.MEDIUM_CHANGED, SenseCategory.RESET -> attentions++
                else -> return false
            }
        }
        return false
    }

    /** Whether the drive still answers a command at all. */
    private fun answers(): Boolean = try {
        drive.testUnitReady()
        true
    } catch (e: IOException) {
        transportFailure(e)
    }

    private fun transportFailure(e: Throwable): Boolean {
        if (e is UsbTransferException || e is BotProtocolException) problem = DriveProblem.DISCONNECTED
        return false
    }

    companion object {
        const val FAILED = -1

        /** The ISO 9660 primary volume descriptor. */
        private const val IDENTITY_BLOCK = 16L

        private const val READY_POLLS = 60
        private const val READY_POLL_MILLIS = 500L

        /** Starts reading the disc of [blockCount] blocks in [drive], noting what tells it from another. */
        fun open(drive: OpticalDrive, blockCount: Long, sleep: (Long) -> Unit = Thread::sleep): DriveReader {
            val identity = ByteArray(OpticalDrive.BLOCK_SIZE)
            drive.read(IDENTITY_BLOCK, 1, identity)
            return DriveReader(drive, blockCount, identity, sleep)
        }
    }
}
