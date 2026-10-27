package be.discrip.core.dvd

import be.discrip.core.estimate.AudioStreamEstimate
import be.discrip.core.estimate.SizeEstimator
import be.discrip.core.model.QualityPreset

/** Size of a rip, before it starts: for the screen and for the free-space check. */
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
        val videoKbps = SizeEstimator.sourceVideoKbps(
            titleBytes = title.sizeBytes,
            durationSeconds = seconds,
            allAudioKbps = title.audio.map(::audioKbps),
            subtitleStreams = title.subtitles.size,
        ) ?: SizeEstimator.TYPICAL_DVD_VIDEO_KBPS
        return SizeEstimator.estimateBytes(seconds, videoKbps, chosenAudio(title, audio), subtitles.size, lpcmToFlac)
    }

    /**
     * Bytes the MKV will take with [preset]: [losslessBytes] for the copy, else the preset's video
     * bit rate ([videoKbps] from the settings, or its default) with the same audio and subtitles.
     */
    fun bytes(
        title: DvdTitle,
        preset: QualityPreset,
        videoKbps: Int?,
        audio: List<AudioTrackPlan>,
        subtitles: List<SubtitleTrackPlan>,
        lpcmToFlac: Boolean,
    ): Long {
        if (!preset.isLossy) return losslessBytes(title, audio, subtitles, lpcmToFlac)
        val kbps = SizeEstimator.videoKbps(preset, videoKbps, measuredSourceVideoKbps = null)
        return SizeEstimator.estimateBytes(title.durationMs / 1000.0, kbps, chosenAudio(title, audio), subtitles.size, lpcmToFlac)
    }

    private fun chosenAudio(title: DvdTitle, audio: List<AudioTrackPlan>): List<AudioStreamEstimate> {
        val byId = title.audio.associateBy { it.streamId }
        return audio.mapNotNull { plan ->
            byId[plan.streamId]?.let { AudioStreamEstimate(audioKbps(it), isLpcm = it.codec == AudioCodec.LPCM) }
        }
    }
}
