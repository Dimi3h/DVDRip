package be.discrip.core.disc

import be.discrip.core.scsi.MediaEvent
import be.discrip.core.scsi.MediaEventStatus
import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.scsi.ReadFailedException
import be.discrip.core.scsi.ScsiCommandException
import be.discrip.core.scsi.SenseCategory
import be.discrip.core.scsi.SenseData
import be.discrip.core.usb.BotProtocolException
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException
import java.io.IOException

sealed interface DiscState {
    /** Not polled yet. */
    data object Unknown : DiscState

    /** No disc; [trayOpen] is null when the drive does not say. */
    data class NoDisc(val trayOpen: Boolean?) : DiscState

    /** A disc is spinning up or being read for the first time. */
    data object Loading : DiscState

    data class Ready(val disc: DiscInfo) : DiscState

    data class Unusable(val problem: DiscProblem) : DiscState
}

/** The drive stopped answering commands, even after reset recovery. */
class DriveUnresponsiveException(cause: IOException) : IOException("The drive stopped responding: ${cause.message}", cause)

/**
 * Follows the disc in a drive by polling: GET EVENT STATUS NOTIFICATION for tray and media
 * events where the drive supports it, TEST UNIT READY for readiness. A disc is probed once when
 * it becomes ready and again after every media change.
 *
 * Not thread-safe: call it from the one thread that talks to the drive.
 */
class DriveMonitor(
    private val drive: OpticalDrive,
    private val probe: (OpticalDrive) -> DiscInfo = DiscProbe::probe,
) {
    var state: DiscState = DiscState.Unknown
        private set

    private var probeNeeded = true
    private var consecutiveFailures = 0

    /**
     * One polling step. Throws [UsbTransferException] when the drive is gone and
     * [DriveUnresponsiveException] after [MAX_CONSECUTIVE_FAILURES] failed polls in a row.
     */
    fun poll(): DiscState {
        val next = try {
            nextState().also { consecutiveFailures = 0 }
        } catch (e: UsbTransferException) {
            if (e.failure == TransferFailure.DISCONNECTED) throw e
            transientFailure(e)
        } catch (e: BotProtocolException) {
            transientFailure(e)
        }
        state = next
        return next
    }

    /** Probes the disc again at the next poll, for example when the user asks to retry. */
    fun invalidate() {
        probeNeeded = true
    }

    /** Unlocks the tray and ejects the disc. */
    fun eject() {
        drive.eject()
        probeNeeded = true
        state = DiscState.NoDisc(trayOpen = true)
    }

    private fun transientFailure(e: IOException): DiscState {
        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) throw DriveUnresponsiveException(e)
        return state
    }

    private fun nextState(): DiscState {
        val media = mediaStatus()
        if (media != null) {
            if (media.event in CHANGE_EVENTS) probeNeeded = true
            if (!media.mediaPresent) {
                probeNeeded = true
                return DiscState.NoDisc(media.trayOpen)
            }
        }

        val sense = readiness()
        if (sense != null) {
            probeNeeded = true
            return when (sense.category) {
                SenseCategory.NO_MEDIUM -> DiscState.NoDisc(media?.trayOpen ?: trayOpen(sense))
                SenseCategory.BECOMING_READY, SenseCategory.NOT_READY, SenseCategory.NO_ERROR -> DiscState.Loading
                else -> DiscState.Unusable(DiscProblem.ReadError(sense, "Drive not ready: $sense"))
            }
        }
        return if (probeNeeded) runProbe() else state
    }

    /** GESN media status, or null when the drive cannot give one now. */
    private fun mediaStatus(): MediaEventStatus? =
        try {
            drive.mediaEventStatus()
        } catch (e: ScsiCommandException) {
            if (e.sense.category in ATTENTION) probeNeeded = true
            null
        }

    /** Null when ready. Unit attentions (medium changed, reset) are consumed and mark a reprobe. */
    private fun readiness(): SenseData? {
        repeat(MAX_UNIT_ATTENTIONS) {
            val sense = drive.testUnitReady() ?: return null
            if (sense.category !in ATTENTION) return sense
            probeNeeded = true
        }
        return drive.testUnitReady()
    }

    private fun runProbe(): DiscState {
        val result = try {
            DiscState.Ready(probe(drive))
        } catch (e: DiscProbeException) {
            DiscState.Unusable(e.problem)
        } catch (e: ScsiCommandException) {
            when (e.sense.category) {
                // The disc went away or is still settling: look again at the next poll.
                SenseCategory.NO_MEDIUM -> return DiscState.NoDisc(trayOpen(e.sense))
                SenseCategory.BECOMING_READY, SenseCategory.NOT_READY,
                SenseCategory.MEDIUM_CHANGED, SenseCategory.RESET -> return DiscState.Loading
                else -> DiscState.Unusable(DiscProblem.ReadError(e.sense, e.message.orEmpty()))
            }
        } catch (e: ReadFailedException) {
            DiscState.Unusable(DiscProblem.ReadError((e.cause as? ScsiCommandException)?.sense, e.message.orEmpty()))
        } catch (e: UsbTransferException) {
            if (e.failure == TransferFailure.DISCONNECTED) throw e
            DiscState.Unusable(DiscProblem.ReadError(null, e.message.orEmpty()))
        } catch (e: IOException) {
            DiscState.Unusable(DiscProblem.ReadError(null, e.message.orEmpty()))
        }
        probeNeeded = false
        return result
    }

    /** ASC 3Ah qualifiers 01h and 02h say whether the tray is closed or open. */
    private fun trayOpen(sense: SenseData): Boolean? = when (sense.ascq) {
        0x01 -> false
        0x02 -> true
        else -> null
    }

    companion object {
        /** Failed polls in a row before the drive counts as unresponsive. */
        const val MAX_CONSECUTIVE_FAILURES = 3

        private const val MAX_UNIT_ATTENTIONS = 4

        private val ATTENTION = setOf(SenseCategory.MEDIUM_CHANGED, SenseCategory.RESET)
        private val CHANGE_EVENTS = setOf(MediaEvent.NEW_MEDIA, MediaEvent.MEDIA_REMOVAL, MediaEvent.MEDIA_CHANGED)
    }
}
