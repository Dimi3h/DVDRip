package be.discrip.rip

import android.content.Context
import be.discrip.R
import be.discrip.core.dvd.AudioPurpose
import be.discrip.core.dvd.AudioTrackPlan
import be.discrip.core.dvd.SubtitlePurpose
import be.discrip.core.dvd.SubtitleTrackPlan

/** Names written into the MKV, in the app's language. */
class RipLabels(private val context: Context) {

    /** Track name for players: only for tracks that are not the film's own sound. */
    fun trackName(track: AudioTrackPlan): String? = when (track.purpose) {
        AudioPurpose.COMMENTARY, AudioPurpose.ALTERNATE_COMMENTARY -> context.getString(R.string.track_commentary)
        AudioPurpose.VISUALLY_IMPAIRED -> context.getString(R.string.track_audio_description)
        AudioPurpose.NORMAL, AudioPurpose.UNSPECIFIED -> null
    }

    /** Track name for subtitles that are not the plain ones. */
    fun subtitleName(track: SubtitleTrackPlan): String? = subtitlePurposeName(track.purpose)?.let(context::getString)

    fun chapterName(number: Int): String = context.getString(R.string.chapter_name, number)

    companion object {
        /** The string resource that names a kind of subtitle stream, or null for plain subtitles. */
        fun subtitlePurposeName(purpose: SubtitlePurpose): Int? = when (purpose) {
            SubtitlePurpose.FORCED -> R.string.subtitle_forced
            SubtitlePurpose.CAPTIONS, SubtitlePurpose.LARGE_CAPTIONS, SubtitlePurpose.CHILDREN_CAPTIONS -> R.string.subtitle_captions
            SubtitlePurpose.COMMENTARY, SubtitlePurpose.LARGE_COMMENTARY, SubtitlePurpose.CHILDREN_COMMENTARY -> R.string.track_commentary
            SubtitlePurpose.LARGE -> R.string.subtitle_large
            SubtitlePurpose.CHILDREN -> R.string.subtitle_children
            SubtitlePurpose.NORMAL, SubtitlePurpose.UNSPECIFIED -> null
        }
    }
}
