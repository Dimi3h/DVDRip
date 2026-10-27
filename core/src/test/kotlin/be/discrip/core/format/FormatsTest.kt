package be.discrip.core.format

import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals

class FormatsTest {

    private val dutch = Locale.forLanguageTag("nl-BE")

    @Test
    fun `sizes use decimal units and the locale's separator`() {
        assertEquals("5,4 GB", Formats.bytes(5_406_732_000, dutch))
        assertEquals("5.4 GB", Formats.bytes(5_406_732_000, Locale.US))
        assertEquals("650 MB", Formats.bytes(650_400_000, dutch))
        assertEquals("12 kB", Formats.bytes(12_345, dutch))
        assertEquals("999 B", Formats.bytes(999, dutch))
        assertEquals("1,2 TB", Formats.bytes(1_230_000_000_000, dutch))
    }

    @Test
    fun `bitrates switch to Mbps above 1000 kbps`() {
        assertEquals("2,5 Mbps", Formats.bitrate(2_500, dutch))
        assertEquals("800 kbps", Formats.bitrate(800, dutch))
    }

    @Test
    fun `speeds are in MB per second`() {
        assertEquals("10,4 MB/s", Formats.speed(10_400_000.0, dutch))
    }

    @Test
    fun `encoding runs in frames per second, its pace against the film as a factor`() {
        assertEquals("143 fps", Formats.framesPerSecond(142.6, dutch))
        assertEquals("7,5 fps", Formats.framesPerSecond(7.46, dutch))
        assertEquals("0,7×", Formats.pace(0.68, dutch))
        assertEquals("12.0×", Formats.pace(12.0, Locale.US))
    }

    @Test
    fun `durations`() {
        assertEquals("2:05:09", Formats.duration(7_509))
        assertEquals("45:02", Formats.duration(2_702))
        assertEquals("0:00", Formats.duration(-5))
    }
}
