package be.discrip.core.dvd

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
)

/** Which title to rip and which of its streams to keep. */
object RipPlanning {

    /** Audio languages that go first, in this order; the rest keep the disc's order. */
    val PREFERRED_LANGUAGES = listOf("nl", "en")

    /**
     * The main feature: the longest title. Ties go to the title with more chapters, then to the
     * lower title number.
     */
    fun mainTitle(titles: List<DvdTitle>): DvdTitle? =
        titles.maxWithOrNull(
            compareBy<DvdTitle> { it.durationMs }.thenBy { it.chapters }.thenByDescending { it.number },
        )

    /**
     * Every audio stream of [title]: Dutch first, then English, then the others in disc order;
     * within a language, commentary and audio description come after the main track. The first
     * main track is the default.
     */
    fun audioTracks(title: DvdTitle): List<AudioTrackPlan> {
        val ordered = title.audio
            .filter { it.codec != AudioCodec.UNKNOWN }
            .sortedWith(
                compareBy<DvdAudioStream> { languageRank(it.language) }
                    .thenBy { if (it.isCommentary || it.purpose == AudioPurpose.VISUALLY_IMPAIRED) 1 else 0 }
                    .thenBy { it.index },
            )
        val default = ordered.firstOrNull { !it.isCommentary && it.purpose != AudioPurpose.VISUALLY_IMPAIRED }
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

    private fun languageRank(language: String?): Int =
        PREFERRED_LANGUAGES.indexOf(language?.lowercase()).let { if (it < 0) PREFERRED_LANGUAGES.size else it }
}
