package be.discrip.core.nas

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NasTargetTest {

    @Test
    fun `hosts as typed in the settings, with or without a port`() {
        assertEquals("nas.local" to 445, NasTarget.of(" nas.local ", "video", "u", "p").let { it.host to it.port })
        assertEquals("192.168.1.10" to 1445, NasTarget.of("192.168.1.10:1445", "video", "u", "p").let { it.host to it.port })
        assertEquals("fd00::2" to 1445, NasTarget.of("[fd00::2]:1445", "video", "u", "p").let { it.host to it.port })
        assertEquals("fd00::2" to 445, NasTarget.of("[fd00::2]", "video", "u", "p").let { it.host to it.port })
        // A bare IPv6 address has more than one colon and no port.
        assertEquals("fd00::2" to 445, NasTarget.of("fd00::2", "video", "u", "p").let { it.host to it.port })
    }

    @Test
    fun `share names lose stray slashes`() {
        assertEquals("video", NasTarget.of("nas", "/video\\", "u", "p").share)
    }
}
