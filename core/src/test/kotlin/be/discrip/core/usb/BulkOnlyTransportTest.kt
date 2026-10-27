package be.discrip.core.usb

import be.discrip.core.scsi.ScsiCommands
import be.discrip.core.testing.MemoryBlockDevice
import be.discrip.core.testing.SimulatedOpticalDrive
import be.discrip.core.testing.TestImages
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BulkOnlyTransportTest {

    private val drive = SimulatedOpticalDrive()
    private val bot = BulkOnlyTransport(drive)

    @BeforeEach
    fun insertDisc() {
        drive.spinUpPolls = 0
        drive.insert(MemoryBlockDevice(TestImages.synthetic()))
        // Clear the unit attention the disc change raised.
        execute(ScsiCommands.testUnitReady())
    }

    private fun execute(cdb: ByteArray, length: Int = 0, buffer: ByteArray = ByteArray(length)) =
        bot.execute(cdb, if (length == 0) DataDirection.NONE else DataDirection.IN, buffer, 0, length, 1_000)

    private fun readCapacity() = execute(ScsiCommands.readCapacity10(), 8)

    @Test
    fun `a command with data passes`() {
        val buffer = ByteArray(8)

        val result = execute(ScsiCommands.readCapacity10(), 8, buffer)

        assertEquals(BulkOnlyTransport.Result(CommandStatus.PASSED, 8, 0), result)
        assertEquals(399, buffer[3].toInt() and 0xFF or ((buffer[2].toInt() and 0xFF) shl 8))
    }

    @Test
    fun `a short answer reports the residue`() {
        val result = execute(ScsiCommands.requestSense(252), 252)

        assertEquals(BulkOnlyTransport.Result(CommandStatus.PASSED, 18, 234), result)
    }

    @Test
    fun `a short answer ended by a stall keeps the bytes that arrived`() {
        drive.stallAfterShortData = true
        val buffer = ByteArray(252)

        val result = execute(ScsiCommands.requestSense(252), 252, buffer)

        assertEquals(BulkOnlyTransport.Result(CommandStatus.PASSED, 18, 234), result)
        assertEquals(0x70, buffer[0].toInt())
        assertEquals(listOf(Endpoint.IN), drive.clearedHalts)
    }

    @Test
    fun `a failed command that stalls the data pipe still yields its status`() {
        drive.removeDisc()

        val result = readCapacity()

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals(8, result.residue)
        assertEquals(listOf(Endpoint.IN), drive.clearedHalts)
        assertEquals(0, drive.resets)
    }

    @Test
    fun `a failed command without a stall needs no recovery`() {
        drive.stallOnFailure = false
        drive.removeDisc()

        val result = readCapacity()

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals(0, result.transferred)
        assertTrue(drive.clearedHalts.isEmpty())
    }

    @Test
    fun `a stall before the status is cleared and the status read again`() {
        drive.statusStalls = 1

        assertEquals(CommandStatus.PASSED, readCapacity().status)
        assertEquals(listOf(Endpoint.IN), drive.clearedHalts)
        assertEquals(0, drive.resets)
    }

    @Test
    fun `a second stall before the status ends in reset recovery`() {
        drive.statusStalls = 2

        val e = assertThrows<UsbTransferException> { readCapacity() }

        assertEquals(TransferFailure.STALL_OR_ERROR, e.failure)
        assertEquals(1, drive.resets)
        assertEquals(CommandStatus.PASSED, readCapacity().status)
    }

    @Test
    fun `an invalid status wrapper triggers reset recovery`() {
        drive.corruptNextStatus = true

        assertThrows<BotProtocolException> { readCapacity() }

        assertEquals(1, drive.resets)
        assertEquals(listOf(Endpoint.IN, Endpoint.OUT), drive.clearedHalts)
        assertEquals(CommandStatus.PASSED, readCapacity().status)
    }

    @Test
    fun `a status for another command triggers reset recovery`() {
        drive.wrongTagNextStatus = true

        assertThrows<BotProtocolException> { readCapacity() }

        assertEquals(1, drive.resets)
    }

    @Test
    fun `a phase error triggers reset recovery`() {
        drive.phaseErrorNextCommand = true

        assertThrows<BotProtocolException> { readCapacity() }

        assertEquals(1, drive.resets)
        assertEquals(CommandStatus.PASSED, readCapacity().status)
    }

    @Test
    fun `a timeout while sending the command triggers reset recovery`() {
        drive.timeouts = 1

        val e = assertThrows<UsbTransferException> { readCapacity() }

        assertEquals(TransferFailure.TIMEOUT, e.failure)
        assertEquals(1, drive.resets)
        assertEquals(CommandStatus.PASSED, readCapacity().status)
    }

    @Test
    fun `a disconnected device is reported without recovery attempts`() {
        drive.disconnected = true

        val e = assertThrows<UsbTransferException> { readCapacity() }

        assertEquals(TransferFailure.DISCONNECTED, e.failure)
        assertEquals(0, drive.resets)
    }

    @Test
    fun `every command carries a new tag`() {
        readCapacity()
        readCapacity()

        assertEquals(listOf(1, 2, 3), drive.tags)
    }
}
