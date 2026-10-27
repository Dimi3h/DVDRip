package be.discrip.core.dvd

import be.discrip.core.estimate.AudioStreamEstimate
import be.discrip.core.estimate.SizeEstimator

/** Size of a lossless rip, before it starts: for the screen and for the free-space check. */
object RipEstimate {

    /**
     * Bit rate of a DVD audio stream. The IFO does not record it for compressed formats, so these
     * are the rates DVDs use most: AC-3 at 192 kbit/s for stereo and 448 for 5.1, DTS at the
     * 768 kbit/s "half rate", MPEG audio at 224. LPCM is exact.
     */
    fun audioKbps(stream: DvdAudioStream): Int = when (stream.codec) {
        AudioCodec.AC3 -> if (stream.channels <= 2) 192 else 448
        AudioCodec.DTS -> 768
        AudioCodec.MP2, AudioCodec.MP2_EXT -> 224
        AudioCodec.LPCM -> stream.sampleRate * stream.bitsPerSample.coerceAtLeast(16) * stream.channels / 1000
        AudioCodec.UNKNOWN -> 192
    }

    /**
     * Bytes the MKV of [title] will take with the [audio] and [subtitles] chosen: the title's
     * video bit rate is what its VOB data leaves after all of its audio and subtitle streams.
     */
    fun losslessBytes(title: DvdTitle, audio: List<AudioTrackPlan>, subtitles: List<SubtitleTrackPlan>, lpcmToFlac: Boolean): Long {
        val seconds = title.durationMs / 1000.0
        val byId = title.audio.associateBy { it.streamId }
        val videoKbps = SizeEstimator.sourceVideoKbps(
            titleBytes = title.sizeBytes,
            durationSeconds = seconds,
            allAudioKbps = title.audio.map(::audioKbps),
            subtitleStreams = title.subtitles.size,
        ) ?: SizeEstimator.TYPICAL_DVD_VIDEO_KBPS
        val chosenAudio = audio.mapNotNull { plan ->
            byId[plan.streamId]?.let { AudioStreamEstimate(audioKbps(it), isLpcm = it.codec == AudioCodec.LPCM) }
        }
        return SizeEstimator.estimateBytes(seconds, videoKbps, chosenAudio, subtitles.size, lpcmToFlac)
    }
}
