package be.discrip.rip

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import be.discrip.core.model.VideoCodec
import be.discrip.core.video.BitrateMode
import be.discrip.core.video.EncoderInfo

/**
 * The HEVC and H.264 encoders of this device, for the lossy presets. Reading them asks the media
 * server, so the first [catalog] belongs off the main thread; the list does not change while the
 * app runs.
 */
class DeviceEncoders(private val source: () -> List<MediaCodecInfo> = { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }) {

    private val catalog: EncoderCatalog by lazy {
        // Aliases are other names of an encoder that is listed anyway.
        EncoderCatalog(source().filter { it.isEncoder && !it.isAlias }.flatMap(::entries))
    }

    fun catalog(): EncoderCatalog = catalog

    private fun entries(info: MediaCodecInfo): List<EncoderCatalog.Entry> = info.supportedTypes.mapNotNull { type ->
        val codec = when (type.lowercase()) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> VideoCodec.HEVC
            MediaFormat.MIMETYPE_VIDEO_AVC -> VideoCodec.H264
            else -> return@mapNotNull null
        }
        val capabilities = try {
            info.getCapabilitiesForType(type)
        } catch (e: IllegalArgumentException) {
            return@mapNotNull null
        }
        val video = capabilities.videoCapabilities ?: return@mapNotNull null
        val encoder = capabilities.encoderCapabilities
        EncoderCatalog.Entry(
            name = info.name,
            codec = codec,
            hardware = info.isHardwareAccelerated,
            takesBuffers = capabilities.colorFormats.any { it in BUFFER_FORMATS },
            fitsSize = video::isSizeSupported,
            bitrateModes = buildSet {
                if (encoder?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_VBR) == true) add(BitrateMode.VBR)
                if (encoder?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) == true) add(BitrateMode.CBR)
            },
        )
    }

    private companion object {
        /**
         * FFmpeg hands MediaCodec the frames in buffers, as NV12 or planar 4:2:0, so an encoder must
         * take one of those or the flexible 4:2:0 that covers both. The first two are deprecated in
         * favour of the third, but encoders still list them and FFmpeg asks for them by name.
         */
        @Suppress("DEPRECATION")
        val BUFFER_FORMATS = setOf(
            CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            CodecCapabilities.COLOR_FormatYUV420Planar,
            CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
    }
}

/** The device's encoders as read once; asking which fit a title is quick. */
class EncoderCatalog(private val entries: List<Entry>) {

    /** One encoder for one codec, as the device describes it. */
    class Entry(
        val name: String,
        val codec: VideoCodec,
        val hardware: Boolean,
        val takesBuffers: Boolean,
        val fitsSize: (width: Int, height: Int) -> Boolean,
        val bitrateModes: Set<BitrateMode>,
    )

    /** Every encoder, in the platform's order of preference, with whether it can take a title of [width] by [height]. */
    fun forTitle(width: Int, height: Int): List<EncoderInfo> = entries.map { entry ->
        EncoderInfo(entry.name, entry.codec, entry.hardware, entry.takesBuffers && entry.fitsSize(width, height), entry.bitrateModes)
    }
}
