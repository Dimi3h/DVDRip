package be.discrip.core.dvd

/** Matroska track flags, as the native engine takes them (DR_TRACK_ in dr_rip.h). */
object TrackFlags {
    const val DEFAULT = 1
    const val FORCED = 2
    const val COMMENTARY = 4
    const val HEARING_IMPAIRED = 8
    const val VISUAL_IMPAIRED = 16
}

/** One audio stream as it goes into the MKV, in output order. */
data class AudioTrackPlan(
    val streamId: Int,
    /** ISO 639-1 code from the disc, or null. */
    val discLanguage: String?,
    /** ISO 639-2/B code written to the MKV, or null for "und". */
    val language: String?,
    val codec: AudioCodec,
    val channels: Int,
    val purpose: AudioPurpose,
    val isDefault: Boolean,
) {
    val flags: Int
        get() = (if (isDefault) TrackFlags.DEFAULT else 0) or when (purpose) {
            AudioPurpose.COMMENTARY, AudioPurpose.ALTERNATE_COMMENTARY -> TrackFlags.COMMENTARY
            AudioPurpose.VISUALLY_IMPAIRED -> TrackFlags.VISUAL_IMPAIRED
            AudioPurpose.NORMAL, AudioPurpose.UNSPECIFIED -> 0
        }
}

/** One subtitle stream as it goes into the MKV, in output order. */
data class SubtitleTrackPlan(
    val streamId: Int,
    /** ISO 639-1 code from the disc, or null. */
    val discLanguage: String?,
    /** ISO 639-2/B code written to the MKV, or null for "und". */
    val language: String?,
    val purpose: SubtitlePurpose,
) {
    val flags: Int
        get() = when {
            purpose == SubtitlePurpose.FORCED -> TrackFlags.FORCED
            purpose.isCaptions -> TrackFlags.HEARING_IMPAIRED
            purpose.isCommentary -> TrackFlags.COMMENTARY
            else -> 0
        }
}

/** Which title to rip and which of its streams to keep. */
object RipPlanning {

    /** Languages that go first, in this order; the rest keep the disc's order. */
    val PREFERRED_LANGUAGES = listOf("nl", "en")

    /**
     * The audio streams of [title] that are in [selected] (all when null): Dutch first, then
     * English, then the others in disc order; within a language, commentary and audio description
     * come after the main track. The default is [defaultStreamId] when it is selected, otherwise
     * the first main track.
     */
    fun audioTracks(title: DvdTitle, selected: Set<Int>? = null, defaultStreamId: Int? = null): List<AudioTrackPlan> {
        val ordered = orderedAudio(title).filter { selected == null || it.streamId in selected }
        val default = ordered.firstOrNull { it.streamId == defaultStreamId }
            ?: ordered.firstOrNull { !it.isCommentary && it.purpose != AudioPurpose.VISUALLY_IMPAIRED }
            ?: ordered.firstOrNull()
        return ordered.map { stream ->
            AudioTrackPlan(
                streamId = stream.streamId,
                discLanguage = stream.language,
                language = Iso639.toBibliographic(stream.language),
                codec = stream.codec,
                channels = stream.channels,
                purpose = stream.purpose,
                isDefault = stream === default,
            )
        }
    }

    /** Every audio stream the engine can copy, in output order. */
    fun orderedAudio(title: DvdTitle): List<DvdAudioStream> =
        title.audio
            .filter { it.codec != AudioCodec.UNKNOWN }
            .sortedWith(
                compareBy<DvdAudioStream> { languageRank(it.language) }
                    .thenBy { if (it.isCommentary || it.purpose == AudioPurpose.VISUALLY_IMPAIRED) 1 else 0 }
                    .thenBy { it.index },
            )

    /**
     * The subtitle streams of [title] that are in [selected] (all when null), Dutch and English
     * first; within a language the plain subtitles, then forced, captions and commentary. None is
     * marked default: players pick subtitles by the viewer's language settings.
     */
    fun subtitleTracks(title: DvdTitle, selected: Set<Int>? = null): List<SubtitleTrackPlan> =
        orderedSubtitles(title)
            .filter { selected == null || it.streamId in selected }
            .map { SubtitleTrackPlan(it.streamId, it.language, Iso639.toBibliographic(it.language), it.purpose) }

    fun orderedSubtitles(title: DvdTitle): List<DvdSubtitleStream> =
        title.subtitles.sortedWith(
            compareBy<DvdSubtitleStream> { languageRank(it.language) }
                .thenBy { subtitleRank(it.purpose) }
                .thenBy { it.index },
        )

    private fun subtitleRank(purpose: SubtitlePurpose): Int = when {
        purpose == SubtitlePurpose.FORCED -> 1
        purpose.isCaptions -> 2
        purpose.isCommentary -> 3
        else -> 0
    }

    private fun languageRank(language: String?): Int =
        PREFERRED_LANGUAGES.indexOf(language?.lowercase()).let { if (it < 0) PREFERRED_LANGUAGES.size else it }
}
