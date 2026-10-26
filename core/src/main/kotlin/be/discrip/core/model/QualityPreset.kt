package be.discrip.core.model

enum class VideoCodec {
    /** MPEG-2 elementary stream copied bit-exact from the disc. */
    COPY,

    /** HEVC through the hardware encoder (hevc_mediacodec). */
    HEVC,

    /** H.264 through the hardware encoder (h264_mediacodec). */
    H264,
}

/**
 * The four quality presets. Labels and descriptions live in the app's string resources; this type
 * carries what the pipeline and the size estimate need. Bitrates are starting values that the user
 * can change in Settings.
 */
enum class QualityPreset(
    val videoCodec: VideoCodec,
    val defaultVideoBitrateKbps: Int?,
) {
    LOSSLESS(VideoCodec.COPY, null),
    BALANCED(VideoCodec.HEVC, 2_500),
    MAX_COMPRESSION(VideoCodec.HEVC, 1_200),
    COMPATIBLE(VideoCodec.H264, 3_000);

    /** Every preset except [LOSSLESS] re-encodes the video and is shown as "met kwaliteitsverlies". */
    val isLossy: Boolean get() = videoCodec != VideoCodec.COPY

    companion object {
        val DEFAULT = LOSSLESS

        /** Below this a DVD-resolution encode falls apart; above it re-encoding stops making sense. */
        const val MIN_VIDEO_BITRATE_KBPS = 300
        const val MAX_VIDEO_BITRATE_KBPS = 12_000

        val lossy: List<QualityPreset> get() = entries.filter { it.isLossy }

        fun clampBitrate(kbps: Int): Int = kbps.coerceIn(MIN_VIDEO_BITRATE_KBPS, MAX_VIDEO_BITRATE_KBPS)
    }
}
