package be.discrip.core.dvd

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Iso639Test {

    @Test
    fun `languages with two ISO 639-2 forms get the bibliographic one`() {
        val expected = mapOf(
            "bo" to "tib", "cs" to "cze", "cy" to "wel", "de" to "ger", "el" to "gre", "eu" to "baq",
            "fa" to "per", "fr" to "fre", "hy" to "arm", "is" to "ice", "ka" to "geo", "mi" to "mao",
            "mk" to "mac", "ms" to "may", "my" to "bur", "nl" to "dut", "ro" to "rum", "sk" to "slo",
            "sq" to "alb", "zh" to "chi",
        )
        for ((code, bibliographic) in expected) assertEquals(bibliographic, Iso639.toBibliographic(code), code)
    }

    @Test
    fun `common DVD languages`() {
        assertEquals("eng", Iso639.toBibliographic("en"))
        assertEquals("spa", Iso639.toBibliographic("es"))
        assertEquals("ita", Iso639.toBibliographic("it"))
        assertEquals("jpn", Iso639.toBibliographic("ja"))
        assertEquals("pol", Iso639.toBibliographic("pl"))
    }

    @Test
    fun `case and spaces do not matter`() {
        assertEquals("dut", Iso639.toBibliographic(" NL "))
    }

    @Test
    fun `withdrawn codes still found on old discs are mapped`() {
        assertEquals("heb", Iso639.toBibliographic("iw"))
        assertEquals("ind", Iso639.toBibliographic("in"))
        assertEquals("yid", Iso639.toBibliographic("ji"))
    }

    @Test
    fun `unknown or missing codes give null`() {
        assertNull(Iso639.toBibliographic(null))
        assertNull(Iso639.toBibliographic("xx"))
        assertNull(Iso639.toBibliographic(""))
    }

    @Test
    fun `maps the codes in a Matroska file back`() {
        assertEquals("nl", Iso639.fromBibliographic("dut"))
        assertEquals("en", Iso639.fromBibliographic("ENG"))
        assertEquals("he", Iso639.fromBibliographic("heb"))
        assertNull(Iso639.fromBibliographic("und"))
        assertNull(Iso639.fromBibliographic(null))
    }

    @Test
    fun `covers all 183 current ISO 639-1 codes`() {
        // Checked against the Library of Congress list (ISO-639-2_utf-8.txt) when the table was written.
        assertEquals(183, Iso639.currentCodeCount)
    }
}
