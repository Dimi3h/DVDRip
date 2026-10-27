package be.discrip.ui.rip

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import be.discrip.R
import be.discrip.core.dvd.AudioCodec
import be.discrip.core.dvd.AudioPurpose
import be.discrip.core.dvd.AudioTrackPlan
import be.discrip.core.dvd.RipFailure
import be.discrip.core.format.Formats
import be.discrip.rip.RipState
import be.discrip.ui.settings.currentLocale
import java.text.NumberFormat
import java.util.Locale

/** "Nederlands", "Engels", or null when the disc gives no usable language. */
fun languageName(code: String?, locale: Locale): String? {
    if (code.isNullOrBlank()) return null
    val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
    if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
    return name.replaceFirstChar { it.titlecase(locale) }
}

/** "5.1", "2.0", "1.0". */
fun channelLayout(channels: Int): String = when (channels) {
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "$channels.0"
}

fun codecName(codec: AudioCodec): String = when (codec) {
    AudioCodec.AC3 -> "Dolby Digital"
    AudioCodec.DTS -> "DTS"
    AudioCodec.LPCM -> "LPCM"
    AudioCodec.MP2, AudioCodec.MP2_EXT -> "MPEG"
    AudioCodec.UNKNOWN -> "?"
}

/** "Dolby Digital 5.1", "LPCM 2.0 → FLAC". */
fun trackFormat(track: AudioTrackPlan, lpcmToFlac: Boolean): String {
    val base = codecName(track.codec) + " " + channelLayout(track.channels)
    return if (track.codec == AudioCodec.LPCM && lpcmToFlac) "$base → FLAC" else base
}

@Composable
fun trackTitle(track: AudioTrackPlan): String {
    val language = languageName(track.discLanguage, currentLocale()) ?: stringResource(R.string.source_language_unknown)
    val purpose = when (track.purpose) {
        AudioPurpose.VISUALLY_IMPAIRED -> stringResource(R.string.track_audio_description)
        AudioPurpose.COMMENTARY, AudioPurpose.ALTERNATE_COMMENTARY -> stringResource(R.string.track_commentary)
        AudioPurpose.NORMAL, AudioPurpose.UNSPECIFIED -> null
    }
    return if (purpose != null) "$language · $purpose" else language
}

fun percent(fraction: Float, locale: Locale): String = NumberFormat.getPercentInstance(locale).format(fraction.toDouble())

/** One line for Home: "42 % · nog 3:10", "Klaar". */
@Composable
fun ripStatusLine(state: RipState): String = when (state) {
    RipState.Idle -> ""
    is RipState.Running -> {
        val progress = state.progress
        when {
            state.cancelling -> stringResource(R.string.rip_cancelling)
            progress == null -> stringResource(R.string.rip_status_starting)
            progress.remainingMs != null -> stringResource(
                R.string.rip_status_percent_left,
                percent(progress.fraction, currentLocale()),
                Formats.duration(progress.remainingMs!! / 1000),
            )
            else -> stringResource(R.string.rip_status_percent, percent(progress.fraction, currentLocale()))
        }
    }
    is RipState.Succeeded -> stringResource(R.string.rip_status_done)
    is RipState.Failed -> stringResource(if (state.reason == RipFailure.CANCELLED) R.string.rip_status_cancelled else R.string.rip_status_failed)
}

@Composable
fun failureText(reason: RipFailure): String = stringResource(
    when (reason) {
        RipFailure.CANCELLED -> R.string.rip_failure_cancelled
        RipFailure.NO_TITLE -> R.string.rip_failure_no_title
        RipFailure.UNREADABLE -> R.string.rip_failure_unreadable
        RipFailure.SCRAMBLED -> R.string.rip_failure_scrambled
        RipFailure.NO_VIDEO -> R.string.rip_failure_no_video
        RipFailure.NO_SPACE -> R.string.rip_failure_no_space
        RipFailure.WRITE_FAILED -> R.string.rip_failure_write_failed
        RipFailure.OUTPUT_UNAVAILABLE -> R.string.rip_failure_output_unavailable
        RipFailure.VERIFY_FAILED -> R.string.rip_failure_verify_failed
        RipFailure.FAILED -> R.string.rip_failure_failed
    },
)
