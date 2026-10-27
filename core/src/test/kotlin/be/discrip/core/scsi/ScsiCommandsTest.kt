package be.discrip.core.scsi

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

class ScsiCommandsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `six byte commands`() {
        assertContentEquals(bytes(0, 0, 0, 0, 0, 0), ScsiCommands.testUnitReady())
        assertContentEquals(bytes(0x03, 0, 0, 0, 18, 0), ScsiCommands.requestSense())
        assertContentEquals(bytes(0x12, 0, 0, 0, 36, 0), ScsiCommands.inquiry())
        assertContentEquals(bytes(0x12, 0, 0, 0x01, 0x00, 0), ScsiCommands.inquiry(256))
        assertContentEquals(bytes(0x1E, 0, 0, 0, 1, 0), ScsiCommands.preventAllowMediumRemoval(true))
        assertContentEquals(bytes(0x1E, 0, 0, 0, 0, 0), ScsiCommands.preventAllowMediumRemoval(false))
    }

    @Test
    fun `start stop unit loads and ejects`() {
        assertContentEquals(bytes(0x1B, 0, 0, 0, 0x02, 0), ScsiCommands.startStopUnit(start = false, loadEject = true))
        assertContentEquals(bytes(0x1B, 0, 0, 0, 0x03, 0), ScsiCommands.startStopUnit(start = true, loadEject = true))
        assertContentEquals(bytes(0x1B, 1, 0, 0, 0x01, 0), ScsiCommands.startStopUnit(start = true, loadEject = false, immediate = true))
    }

    @Test
    fun `read capacity`() {
        assertContentEquals(bytes(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), ScsiCommands.readCapacity10())
    }

    @Test
    fun `read 10 puts lba and length big-endian`() {
        assertContentEquals(
            bytes(0x28, 0, 0x12, 0x34, 0x56, 0x78, 0, 0x00, 0x20, 0),
            ScsiCommands.read10(0x12345678, 32),
        )
        assertContentEquals(
            bytes(0x28, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0xFF, 0xFF, 0),
            ScsiCommands.read10(0xFFFF_FFFFL, 0xFFFF),
        )
    }

    @Test
    fun `read 12 has a 32-bit length`() {
        assertContentEquals(
            bytes(0xA8, 0, 0, 0, 0x01, 0x00, 0, 0x01, 0x00, 0x00, 0, 0),
            ScsiCommands.read12(256, 65_536),
        )
    }

    @Test
    fun `read arguments are checked`() {
        assertThrows<IllegalArgumentException> { ScsiCommands.read10(-1, 1) }
        assertThrows<IllegalArgumentException> { ScsiCommands.read10(0x1_0000_0000L, 1) }
        assertThrows<IllegalArgumentException> { ScsiCommands.read10(0, 65_536) }
        assertThrows<IllegalArgumentException> { ScsiCommands.read12(0, -1) }
    }

    @Test
    fun `get event status notification asks for polled media events`() {
        assertContentEquals(
            bytes(0x4A, 0x01, 0, 0, 0x10, 0, 0, 0x00, 0x08, 0),
            ScsiCommands.getEventStatusNotification(),
        )
    }

    @Test
    fun `get configuration asks for the header`() {
        assertContentEquals(
            bytes(0x46, 0x02, 0, 0, 0, 0, 0, 0x00, 0x08, 0),
            ScsiCommands.getConfiguration(),
        )
        assertContentEquals(
            bytes(0x46, 0x01, 0x01, 0x07, 0, 0, 0, 0x01, 0x00, 0),
            ScsiCommands.getConfiguration(requestType = 0x01, startingFeature = 0x0107, allocationLength = 256),
        )
    }

    @Test
    fun `read dvd structure for copyright information`() {
        assertContentEquals(
            bytes(0xAD, 0, 0, 0, 0, 0, 0, 0x01, 0x00, 0x08, 0, 0),
            ScsiCommands.readDvdStructure(ScsiCommands.DVD_STRUCTURE_COPYRIGHT, allocationLength = 8),
        )
        assertContentEquals(
            bytes(0xAD, 0, 0, 0, 0, 0, 1, 0x02, 0x08, 0x04, 0x80, 0),
            ScsiCommands.readDvdStructure(format = 0x02, layer = 1, allocationLength = 2052, agid = 2),
        )
    }

    @Test
    fun `report key for the rpc state`() {
        assertContentEquals(
            bytes(0xA4, 0, 0, 0, 0, 0, 0, 0, 0x00, 0x08, 0x08, 0),
            ScsiCommands.reportKey(ScsiCommands.KEY_FORMAT_RPC_STATE, allocationLength = 8),
        )
        assertContentEquals(
            bytes(0xA4, 0, 0x00, 0x01, 0x00, 0x00, 0, 0, 0x00, 0x0C, 0xC4, 0),
            ScsiCommands.reportKey(keyFormat = 0x04, allocationLength = 12, agid = 3, lba = 65_536),
        )
    }
}
