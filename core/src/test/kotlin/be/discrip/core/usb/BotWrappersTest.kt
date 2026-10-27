package be.discrip.core.usb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BotWrappersTest {

    private val read10 = byteArrayOf(0x28, 0, 0, 0, 0x10, 0, 0, 0, 0x20, 0)

    @Test
    fun `command block wrapper has the BOT layout`() {
        val bytes = CommandBlockWrapper(0x12345678, 65_536, DataDirection.IN, 0, read10).encode()

        assertEquals(31, bytes.size)
        assertContentEquals("USBC".toByteArray(), bytes.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), bytes.copyOfRange(4, 8))
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x01, 0x00), bytes.copyOfRange(8, 12))
        assertEquals(0x80.toByte(), bytes[12])
        assertEquals(0, bytes[13].toInt())
        assertEquals(10, bytes[14].toInt())
        assertContentEquals(read10, bytes.copyOfRange(15, 25))
        assertContentEquals(ByteArray(6), bytes.copyOfRange(25, 31))
    }

    @Test
    fun `data-out and no-data commands clear the direction bit`() {
        val out = CommandBlockWrapper(1, 20, DataDirection.OUT, 2, byteArrayOf(0xA3.toByte(), 0)).encode()
        val none = CommandBlockWrapper(1, 0, DataDirection.NONE, 0, ByteArray(6)).encode()

        assertEquals(0, out[12].toInt())
        assertEquals(2, out[13].toInt())
        assertEquals(0, none[12].toInt())
    }

    @Test
    fun `command block wrapper survives a round trip`() {
        val original = CommandBlockWrapper(-7, 2048, DataDirection.IN, 1, read10)

        val decoded = assertNotNull(CommandBlockWrapper.decode(original.encode()))

        assertEquals(-7, decoded.tag)
        assertEquals(2048, decoded.dataTransferLength)
        assertEquals(DataDirection.IN, decoded.direction)
        assertEquals(1, decoded.lun)
        assertContentEquals(read10, decoded.commandBlock)
    }

    @Test
    fun `malformed command block wrappers are rejected`() {
        val good = CommandBlockWrapper(1, 0, DataDirection.NONE, 0, ByteArray(6)).encode()

        assertNull(CommandBlockWrapper.decode(good.copyOf(30), 30))
        assertNull(CommandBlockWrapper.decode(good.copyOf().also { it[0] = 0 }))
        assertNull(CommandBlockWrapper.decode(good.copyOf().also { it[14] = 0 }))
        assertNull(CommandBlockWrapper.decode(good.copyOf().also { it[14] = 17 }))
    }

    @Test
    fun `invalid wrappers cannot be built`() {
        assertThrows<IllegalArgumentException> { CommandBlockWrapper(1, 0, DataDirection.NONE, 0, ByteArray(17)) }
        assertThrows<IllegalArgumentException> { CommandBlockWrapper(1, 0, DataDirection.NONE, 0, ByteArray(0)) }
        assertThrows<IllegalArgumentException> { CommandBlockWrapper(1, 8, DataDirection.NONE, 0, ByteArray(6)) }
        assertThrows<IllegalArgumentException> { CommandBlockWrapper(1, 0, DataDirection.NONE, 16, ByteArray(6)) }
    }

    @Test
    fun `command status wrapper is parsed`() {
        val bytes = byteArrayOf(
            0x55, 0x53, 0x42, 0x53, // "USBS"
            0x2A, 0, 0, 0, // tag 42
            0x00, 0x08, 0, 0, // residue 2048
            1, // failed
        )

        assertEquals(CommandStatusWrapper(42, 2048, CommandStatus.FAILED), CommandStatusWrapper.decode(bytes))
    }

    @Test
    fun `command status wrapper survives a round trip`() {
        for (status in CommandStatus.entries) {
            val csw = CommandStatusWrapper(9, 3, status)
            assertEquals(csw, CommandStatusWrapper.decode(csw.encode()))
        }
    }

    @Test
    fun `malformed command status wrappers are rejected`() {
        val good = CommandStatusWrapper(1, 0, CommandStatus.PASSED).encode()

        assertNull(CommandStatusWrapper.decode(good.copyOf(12), 12))
        assertNull(CommandStatusWrapper.decode(good.copyOf().also { it[3] = 0x43 }))
        assertNull(CommandStatusWrapper.decode(good.copyOf().also { it[12] = 3 }))
    }
}
