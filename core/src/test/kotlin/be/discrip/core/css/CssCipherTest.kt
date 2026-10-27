package be.discrip.core.css

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CssCipherTest {

    /** 288 results of libdvdcss's own CryptKey (`css_tool vectors`): every key type and variant. */
    @Test
    fun `matches libdvdcss for every key type and variant`() {
        val lines = javaClass.getResource("/css/cryptkey-vectors.txt")!!.readText().lines().filter { it.isNotBlank() }
        assertEquals(288, lines.size)
        for (line in lines) {
            val (type, variant, challenge, key) = line.split(" ")
            assertEquals(key, CssCipher.cryptKey(type.toInt(), variant.toInt(), challenge.hex()).toHex(), line)
        }
    }

    private fun String.hex() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
