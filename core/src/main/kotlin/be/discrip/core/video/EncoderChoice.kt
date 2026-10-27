package be.discrip.core.video

import be.discrip.core.model.QualityPreset
import be.discrip.core.model.VideoCodec

/** How a MediaCodec encoder keeps to its bit rate; [ffmpegName] is FFmpeg's bitrate_mode value. */
enum class BitrateMode(val ffmpegName: String) {
    /** Variable: more bits where the picture needs them, as the presets want. */
    VBR("vbr"),

    /** Constant, for encoders that cannot vary it. */
    CBR("cbr"),
}

/** A video encoder of the device (from MediaCodecList), as far as choosing one needs. */
data class EncoderInfo(
    /** The MediaCodec component, such as "c2.qti.hevc.encoder". */
    val name: String,
    val codec: VideoCodec,
    /** Runs on dedicated hardware rather than on the CPU. */
    val hardware: Boolean,
    /** Takes frames of the title's size, from buffers as FFmpeg hands them over. */
    val fitsTitle: Boolean,
    val bitrateModes: Set<BitrateMode>,
)

/** The encoder a lossy rip uses. */
data class VideoEncoding(
    val codec: VideoCodec,
    /** The MediaCodec component. */
    val component: String,
    val hardware: Boolean,
    /** Null leaves the component's own mode. */
    val bitrateMode: BitrateMode?,
) {
    /** FFmpeg's name for MediaCodec's encoder of [codec]. */
    val ffmpegEncoder: String
        get() = when (codec) {
            VideoCodec.HEVC -> "hevc_mediacodec"
            VideoCodec.H264 -> "h264_mediacodec"
            VideoCodec.COPY -> throw IllegalStateException("The copy has no encoder")
        }
}

/** How the encoder of a rip departs from what its preset asks. */
enum class EncoderFallback {
    /** A hardware encoder of the preset's codec, as asked. */
    NONE,

    /** The device has no hardware encoder for HEVC: H.264 on the hardware instead. */
    H264_INSTEAD_OF_HEVC,

    /** The device has no hardware encoder that fits: a software one, which takes longer. */
    SOFTWARE,
}

/** The video of a lossy rip: its bit rate, and the encoders to try in turn while one will not start. */
data class VideoPlan(
    val preset: QualityPreset,
    val bitRateKbps: Int,
    /** Best first; never empty. */
    val encoders: List<VideoEncoding>,
) {
    init {
        require(encoders.isNotEmpty()) { "A video plan needs an encoder" }
    }

    /** The encoder the rip starts with. */
    val encoder: VideoEncoding get() = encoders.first()

    fun fallbackOf(encoding: VideoEncoding = encoder): EncoderFallback = when {
        !encoding.hardware -> EncoderFallback.SOFTWARE
        encoding.codec != preset.videoCodec -> EncoderFallback.H264_INSTEAD_OF_HEVC
        else -> EncoderFallback.NONE
    }
}

/** Picks the encoders for a lossy preset from those the device has. */
object EncoderChoice {

    /**
     * The encoders for [preset] at [bitRateKbps], best first: the hardware encoders of its codec;
     * for HEVC then H.264 on the hardware; then software encoders in the same order. Each keeps
     * the preset's bit rate, so the file comes out at the size shown, also with H.264. Null for
     * the lossless copy, and when the device has no encoder that fits the title.
     */
    fun plan(preset: QualityPreset, bitRateKbps: Int, available: List<EncoderInfo>): VideoPlan? {
        if (!preset.isLossy) return null
        val codecs = if (preset.videoCodec == VideoCodec.HEVC) listOf(VideoCodec.HEVC, VideoCodec.H264) else listOf(preset.videoCodec)
        val fitting = available.filter { it.fitsTitle }
        val ordered = listOf(true, false).flatMap { hardware ->
            codecs.flatMap { codec -> fitting.filter { it.hardware == hardware && it.codec == codec } }
        }
        val encoders = ordered.map { info -> VideoEncoding(info.codec, info.name, info.hardware, modeFor(info)) }.distinct()
        if (encoders.isEmpty()) return null
        return VideoPlan(preset, QualityPreset.clampBitrate(bitRateKbps), encoders)
    }

    private fun modeFor(info: EncoderInfo): BitrateMode? = when {
        BitrateMode.VBR in info.bitrateModes -> BitrateMode.VBR
        BitrateMode.CBR in info.bitrateModes -> BitrateMode.CBR
        else -> null
    }
}
