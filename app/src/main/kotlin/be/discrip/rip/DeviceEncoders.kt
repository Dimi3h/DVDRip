package be.discrip.rip

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import be.discrip.core.model.VideoCodec
import be.discrip.core.video.BitrateMode
import be.discrip.core.video.BufferFormat
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
            bufferFormat = bufferFormat(capabilities.colorFormats.toSet()),
            fitsSize = video::isSizeSupported,
            bitrateModes = buildSet {
                if (encoder?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_VBR) == true) add(BitrateMode.VBR)
                if (encoder?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) == true) add(BitrateMode.CBR)
            },
        )
    }

    private companion object {
        /**
         * The layout in which the engine hands frames to an encoder that lists [formats]: NV12 where
         * it lists that, or only the flexible 4:2:0, which the platform fills from either; planar
         * 4:2:0 where it lists that and not NV12; null where it lists neither. The two are deprecated
         * in favour of the flexible format, but encoders still list them, and only they fix the
         * layout of a buffer, which the engine writes without Java's Image.
         */
        @Suppress("DEPRECATION")
        fun bufferFormat(formats: Set<Int>): BufferFormat? = when {
            CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats -> BufferFormat.NV12
            CodecCapabilities.COLOR_FormatYUV420Planar in formats -> BufferFormat.I420
            CodecCapabilities.COLOR_FormatYUV420Flexible in formats -> BufferFormat.NV12
            else -> null
        }
    }
}

/** The device's encoders as read once; asking which fit a title is quick. */
class EncoderCatalog(private val entries: List<Entry>) {

    /** One encoder for one codec, as the device describes it. */
    class Entry(
        val name: String,
        val codec: VideoCodec,
        val hardware: Boolean,
        /** Null when it takes no frames in buffers the engine can fill. */
        val bufferFormat: BufferFormat?,
        val fitsSize: (width: Int, height: Int) -> Boolean,
        val bitrateModes: Set<BitrateMode>,
    )

    /** Every encoder, in the platform's order of preference, with whether it can take a title of [width] by [height]. */
    fun forTitle(width: Int, height: Int): List<EncoderInfo> = entries.map { entry ->
        EncoderInfo(
            name = entry.name,
            codec = entry.codec,
            hardware = entry.hardware,
            fitsTitle = entry.bufferFormat != null && entry.fitsSize(width, height),
            bitrateModes = entry.bitrateModes,
            // Only read where it fits.
            bufferFormat = entry.bufferFormat ?: BufferFormat.NV12,
        )
    }
}
