package be.discrip.core.format

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Locale-aware number formatting with decimal (SI) units, as Apple platforms show file sizes. */
object Formats {

    fun bytes(bytes: Long, locale: Locale): String {
        val value = abs(bytes.toDouble())
        val sign = if (bytes < 0) "-" else ""
        return when {
            value >= 1e12 -> sign + decimal(value / 1e12, 1, locale) + " TB"
            value >= 1e9 -> sign + decimal(value / 1e9, 1, locale) + " GB"
            value >= 1e6 -> sign + decimal(value / 1e6, 0, locale) + " MB"
            value >= 1e3 -> sign + decimal(value / 1e3, 0, locale) + " kB"
            else -> "$sign${value.toLong()} B"
        }
    }

    fun bitrate(kbps: Int, locale: Locale): String =
        if (kbps >= 1000) decimal(kbps / 1000.0, 1, locale) + " Mbps" else "$kbps kbps"

    fun speed(bytesPerSecond: Double, locale: Locale): String = decimal(bytesPerSecond / 1e6, 1, locale) + " MB/s"

    /** `143 fps`; one decimal below 10. */
    fun framesPerSecond(fps: Double, locale: Locale): String = decimal(fps, if (fps < 10) 1 else 0, locale) + " fps"

    /** How fast against playing the film: `1,6×`. */
    fun pace(speed: Double, locale: Locale): String = decimal(speed, 1, locale) + "×"

    /** `1:05:09` for durations of an hour or more, `45:02` below that. */
    fun duration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }

    private fun decimal(value: Double, fractionDigits: Int, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale)
        format.minimumFractionDigits = fractionDigits
        format.maximumFractionDigits = fractionDigits
        return format.format(value)
    }
}
