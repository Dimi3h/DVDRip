package be.discrip.core.testing

import org.junit.jupiter.api.Test
import java.util.zip.GZIPInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CssScramblerTest {

    /**
     * The first six blocks of the test DVD's title VOB (a navigation pack, then video and audio
     * packs), clear and as libdvdcss's own cipher scrambles them (css_tool scramble, key
     * 0123456789).
     */
    private fun packs(name: String): ByteArray {
        val stream = CssScramblerTest::class.java.getResourceAsStream("/css/$name.bin.gz") ?: error("No $name")
        return GZIPInputStream(stream).use { it.readBytes() }
    }

    @Test
    fun `scrambles as libdvdcss does`() {
        val data = packs("packs-clear")
        val key = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte())

        val scrambled = (0 until data.size / CssScrambler.BLOCK_SIZE).map { CssScrambler.scramble(data, it * CssScrambler.BLOCK_SIZE, key) }

        assertEquals(listOf(false, true, true, true, true, true), scrambled)
        assertContentEquals(packs("packs-scrambled"), data)
    }

    @Test
    fun `navigation packs and other sectors stay clear`() {
        val data = packs("packs-clear")
        val navigation = data.copyOf(CssScrambler.BLOCK_SIZE)
        val key = ByteArray(5) { 7 }

        assertFalse(CssScrambler.scramble(navigation, 0, key))
        assertContentEquals(data.copyOf(CssScrambler.BLOCK_SIZE), navigation)
        assertFalse(CssScrambler.scramble(ByteArray(CssScrambler.BLOCK_SIZE), 0, key))
        assertTrue(CssScrambler.scramble(data, CssScrambler.BLOCK_SIZE, key))
    }
}
