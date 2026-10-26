package be.discrip.core.estimate

import be.discrip.core.model.QualityPreset
import kotlin.math.roundToLong

/** One audio stream that will be written to the MKV. */
data class AudioStreamEstimate(
    val kbps: Int,
    /** LPCM can be converted to FLAC, which is lossless but smaller. */
    val isLpcm: Boolean = false,
)

/**
 * Estimates output sizes from duration and bitrates. Used for the per-preset figure in the UI and
 * for the free-space check before every rip.
 */
object SizeEstimator {

    /** Average MPEG-2 video bitrate of a typical feature film DVD, used when nothing is measured. */
    const val TYPICAL_DVD_VIDEO_KBPS = 5_500

    /** Dolby Digital 5.1 as found on most film DVDs. */
    const val TYPICAL_AC3_KBPS = 448

    /** DVD subtitles are small bitmaps; a few kbit/s on average. */
    const val VOBSUB_KBPS = 10

    /** FLAC typically shrinks DVD LPCM to roughly 60 %. */
    const val FLAC_RATIO = 0.6

    /** Matroska clusters, cues and block headers. */
    const val CONTAINER_OVERHEAD = 1.01

    /** MPEG program stream pack and PES headers on the disc. */
    const val PROGRAM_STREAM_OVERHEAD = 1.025

    /** Free space we demand on top of the estimate before starting a rip. */
    const val FREE_SPACE_MARGIN = 1.10

    const val REFERENCE_DURATION_SECONDS = 2 * 60 * 60

    fun estimateBytes(
        durationSeconds: Double,
        videoKbps: Int,
        audio: List<AudioStreamEstimate>,
        subtitleStreams: Int,
        lpcmToFlac: Boolean,
    ): Long {
        if (durationSeconds <= 0.0) return 0
        val audioKbps = audio.sumOf { stream ->
            if (stream.isLpcm && lpcmToFlac) stream.kbps * FLAC_RATIO else stream.kbps.toDouble()
        }
        val totalKbps = videoKbps + audioKbps + subtitleStreams * VOBSUB_KBPS
        return (durationSeconds * totalKbps * 1000.0 / 8.0 * CONTAINER_OVERHEAD).roundToLong()
    }

    /**
     * The video bitrate a preset will produce: the configured bitrate for lossy presets, the
     * disc's own bitrate for the lossless copy.
     */
    fun videoKbps(preset: QualityPreset, configuredKbps: Int?, measuredSourceVideoKbps: Int?): Int =
        if (preset.isLossy) {
            QualityPreset.clampBitrate(configuredKbps ?: preset.defaultVideoBitrateKbps ?: TYPICAL_DVD_VIDEO_KBPS)
        } else {
            measuredSourceVideoKbps ?: TYPICAL_DVD_VIDEO_KBPS
        }

    /**
     * Video bitrate of a title on disc, from the size of its VOB data minus every audio and subtitle
     * stream it contains. Returns null when the numbers are not plausible.
     */
    fun sourceVideoKbps(
        titleBytes: Long,
        durationSeconds: Double,
        allAudioKbps: List<Int>,
        subtitleStreams: Int,
    ): Int? {
        if (titleBytes <= 0 || durationSeconds <= 0.0) return null
        val totalKbps = titleBytes * 8.0 / 1000.0 / durationSeconds / PROGRAM_STREAM_OVERHEAD
        val videoKbps = totalKbps - allAudioKbps.sum() - subtitleStreams * VOBSUB_KBPS
        return videoKbps.takeIf { it > 0 }?.toInt()
    }

    /** Reference figure for Settings: a two-hour film with one AC-3 5.1 track and no subtitles. */
    fun referenceBytes(preset: QualityPreset, configuredKbps: Int?): Long =
        estimateBytes(
            durationSeconds = REFERENCE_DURATION_SECONDS.toDouble(),
            videoKbps = videoKbps(preset, configuredKbps, measuredSourceVideoKbps = null),
            audio = listOf(AudioStreamEstimate(TYPICAL_AC3_KBPS)),
            subtitleStreams = 0,
            lpcmToFlac = false,
        )

    /** Bytes that must be free before a rip of [estimatedBytes] may start. */
    fun requiredFreeBytes(estimatedBytes: Long): Long = (estimatedBytes * FREE_SPACE_MARGIN).roundToLong()
}
