package be.discrip.core.disc

import be.discrip.core.scsi.DiscType
import be.discrip.core.scsi.OpticalDrive
import java.io.IOException

/** The disc is there but cannot be used; [problem] says why. */
class DiscProbeException(val problem: DiscProblem) : IOException(problem.toString())

/** Reads what DiscRip needs to know about a disc before ripping it. */
object DiscProbe {

    /** Smallest image that still holds the volume recognition area. */
    private const val MIN_BLOCKS = 17L

    /**
     * Probes the disc in a drive that reported ready. Throws [DiscProbeException] for media
     * DiscRip does not handle; drive errors propagate as they are.
     */
    fun probe(drive: OpticalDrive): DiscInfo {
        val profile = drive.currentProfile()
        if (profile != null && profile.type in REJECTED_TYPES) throw DiscProbeException(DiscProblem.WrongMedium(profile))
        val capacity = drive.readCapacity()
        if (capacity.blockLength != BlockDevice.BLOCK_SIZE) {
            throw DiscProbeException(DiscProblem.ReadError(null, "Unexpected block length ${capacity.blockLength}"))
        }
        if (capacity.blockCount < MIN_BLOCKS) throw DiscProbeException(DiscProblem.Blank)

        val volume = VolumeReader.read(OpticalDriveBlockDevice(drive, capacity.blockCount))
        val copyright = drive.copyrightInfo()
        val rpc = drive.rpcState()
        return DiscInfo(
            volume = volume,
            blockCount = capacity.blockCount,
            profile = profile,
            cssProtected = copyright?.isCss,
            region = RegionStatus.evaluate(copyright, rpc),
        )
    }

    /** Probes an ISO image or other block device without a drive behind it. */
    fun probe(image: BlockDevice): DiscInfo {
        if (image.blockCount < MIN_BLOCKS) throw DiscProbeException(DiscProblem.Blank)
        return DiscInfo(
            volume = VolumeReader.read(image),
            blockCount = image.blockCount,
            profile = null,
            cssProtected = null,
            region = null,
        )
    }

    private val REJECTED_TYPES = setOf(DiscType.CD, DiscType.BLU_RAY, DiscType.HD_DVD)
}
