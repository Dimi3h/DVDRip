package be.discrip.core.disc

import be.discrip.core.scsi.CopyrightInfo
import be.discrip.core.scsi.DiscProfile
import be.discrip.core.scsi.RpcState
import be.discrip.core.scsi.SenseData

/** A disc (or disc image) that could be read. */
data class DiscInfo(
    val volume: VolumeInfo,
    val blockCount: Long,
    /** MMC profile of the medium; null for images and for drives without GET CONFIGURATION. */
    val profile: DiscProfile?,
    /** Whether the disc is CSS protected; null when unknown (images, drives that do not say). */
    val cssProtected: Boolean?,
    /** Region check between drive and disc; null for images. */
    val region: RegionStatus?,
) {
    val label: String? get() = volume.label
    val sizeBytes: Long get() = blockCount * BlockDevice.BLOCK_SIZE
    val isDvdVideo: Boolean get() = volume.hasRootDirectory(VIDEO_TS)

    companion object {
        const val VIDEO_TS = "VIDEO_TS"
    }
}

enum class RegionVerdict {
    /** The disc is not CSS protected, or the drive does not enforce regions (RPC-I). */
    NOT_ENFORCED,
    /** The drive's region is one the disc allows. */
    MATCH,
    /** An RPC-II drive whose region was never set refuses scrambled sectors. */
    DRIVE_REGION_NOT_SET,
    /** The drive is set to a region the disc does not allow. */
    MISMATCH,
    /** The drive or disc did not report enough to tell. */
    UNKNOWN,
}

data class RegionStatus(
    /** Regions the disc allows; empty when it is not CSS protected or did not say. */
    val discRegions: Set<Int>,
    /** Regions the drive plays; null when it does not enforce or report a region. */
    val driveRegions: Set<Int>?,
    /** Region changes the user has left on the drive, when it reports them. */
    val changesLeft: Int?,
    val verdict: RegionVerdict,
) {
    companion object {
        fun evaluate(copyright: CopyrightInfo?, rpc: RpcState?): RegionStatus {
            val enforcing = rpc?.takeIf { it.enforcesRegion }
            val verdict = when {
                copyright == null -> RegionVerdict.UNKNOWN
                !copyright.isCss -> RegionVerdict.NOT_ENFORCED
                rpc == null -> RegionVerdict.UNKNOWN
                enforcing == null -> RegionVerdict.NOT_ENFORCED
                !enforcing.regionSet -> RegionVerdict.DRIVE_REGION_NOT_SET
                (enforcing.regionMask or copyright.regionMask) and 0xFF == 0xFF -> RegionVerdict.MISMATCH
                else -> RegionVerdict.MATCH
            }
            return RegionStatus(
                discRegions = copyright?.playableRegions.orEmpty(),
                driveRegions = enforcing?.takeIf { it.regionSet }?.playableRegions,
                changesLeft = enforcing?.userChangesLeft,
                verdict = verdict,
            )
        }
    }
}

/** Why a disc cannot be used. */
sealed interface DiscProblem {
    /** A CD, Blu-ray or other medium that is not a DVD. */
    data class WrongMedium(val profile: DiscProfile) : DiscProblem

    /** A recordable disc with nothing on it. */
    data object Blank : DiscProblem

    /** The disc could not be read; [sense] is the drive's reason when it gave one. */
    data class ReadError(val sense: SenseData?, val message: String) : DiscProblem
}
