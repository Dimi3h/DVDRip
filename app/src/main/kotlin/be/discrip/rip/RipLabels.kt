package be.discrip.rip

import android.content.Context
import be.discrip.R
import be.discrip.core.dvd.AudioPurpose
import be.discrip.core.dvd.AudioTrackPlan

/** Names written into the MKV, in the app's language. */
class RipLabels(private val context: Context) {

    /** Track name for players: only for tracks that are not the film's own sound. */
    fun trackName(track: AudioTrackPlan): String? = when (track.purpose) {
        AudioPurpose.COMMENTARY, AudioPurpose.ALTERNATE_COMMENTARY -> context.getString(R.string.track_commentary)
        AudioPurpose.VISUALLY_IMPAIRED -> context.getString(R.string.track_audio_description)
        AudioPurpose.NORMAL, AudioPurpose.UNSPECIFIED -> null
    }

    fun chapterName(number: Int): String = context.getString(R.string.chapter_name, number)
}
