package be.discrip.core.scsi

import be.discrip.core.disc.BlockDevice
import be.discrip.core.testing.MemoryBlockDevice
import be.discrip.core.testing.SimulatedOpticalDrive
import be.discrip.core.testing.SimulatedOpticalDrive.ReadFault
import be.discrip.core.testing.SimulatedOpticalDrive.Sense
import be.discrip.core.testing.TestImages
import be.discrip.core.usb.BulkOnlyTransport
import be.discrip.core.usb.TransferFailure
import be.discrip.core.usb.UsbTransferException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpticalDriveTest {

    private val simulator = SimulatedOpticalDrive()
    private val drive = OpticalDrive(BulkOnlyTransport(simulator))
    private val image = TestImages.synthetic()

    /** Inserts the image and waits until the drive is ready, as the monitor would. */
    private fun ready(): OpticalDrive {
        simulator.insert(MemoryBlockDevice(image))
        repeat(10) { if (drive.testUnitReady() == null) return drive }
        error("Drive never became ready")
    }

    private fun reads() = simulator.count(ScsiCommands.READ_10)

    @Test
    fun `inquiry identifies the drive`() {
        val inquiry = drive.inquiry()

        assertTrue(inquiry.isOpticalDrive)
        assertEquals("DISCRIP SIMULATED DVD", inquiry.displayName)
    }

    @Test
    fun `test unit ready reports why the drive is not ready`() {
        assertEquals(SenseCategory.RESET, drive.testUnitReady()?.category)
        assertEquals(SenseData(2, 0x3A, 0x01), drive.testUnitReady())

        simulator.openTray()
        assertEquals(SenseData(2, 0x3A, 0x02), drive.testUnitReady())

        simulator.insert(MemoryBlockDevice(image))
        assertEquals(SenseCategory.MEDIUM_CHANGED, drive.testUnitReady()?.category)
        assertEquals(SenseCategory.BECOMING_READY, drive.testUnitReady()?.category)
        assertEquals(SenseCategory.BECOMING_READY, drive.testUnitReady()?.category)
        assertNull(drive.testUnitReady())
    }

    @Test
    fun `capacity of the disc`() {
        ready()

        val capacity = drive.readCapacity()

        assertEquals(image.size / 2048L, capacity.blockCount)
        assertEquals(2048, capacity.blockLength)
    }

    @Test
    fun `reads are split into 32-block commands`() {
        ready()
        val buffer = ByteArray(70 * BlockDevice.BLOCK_SIZE)

        drive.read(5, 70, buffer)

        assertContentEquals(image.copyOfRange(5 * 2048, 75 * 2048), buffer)
        val reads = simulator.commands.filter { it[0] == ScsiCommands.READ_10.toByte() }
        assertEquals(listOf(32, 32, 6), reads.map { it[8].toInt() })
        assertEquals(listOf(5, 37, 69), reads.map { it[5].toInt() })
    }

    @Test
    fun `reads honour the buffer offset`() {
        ready()
        val buffer = ByteArray(3 * BlockDevice.BLOCK_SIZE)

        drive.read(16, 1, buffer, offset = 2048)

        assertContentEquals(image.copyOfRange(16 * 2048, 17 * 2048), buffer.copyOfRange(2048, 4096))
        assertThrows<IllegalArgumentException> { drive.read(16, 2, buffer, offset = 4096) }
    }

    @Test
    fun `transient read errors are retried`() {
        ready()
        simulator.readFaults[40] = ReadFault(Sense(0x03, 0x11, 0x00), times = 3)

        drive.read(0, 70, ByteArray(70 * BlockDevice.BLOCK_SIZE))

        assertEquals(6, reads())
    }

    @Test
    fun `a block range that keeps failing is given up after three retries`() {
        ready()
        simulator.readFaults[40] = ReadFault(Sense(0x03, 0x11, 0x00), times = 10)

        val e = assertThrows<ReadFailedException> { drive.read(0, 70, ByteArray(70 * BlockDevice.BLOCK_SIZE)) }

        assertEquals(32, e.lba)
        assertEquals(32, e.blocks)
        assertEquals(SenseData(3, 0x11, 0), (e.cause as ScsiCommandException).sense)
        assertEquals(1 + 4, reads())
    }

    @Test
    fun `a region mismatch fails at once`() {
        ready()
        simulator.readFaults[300] = ReadFault(Sense(0x05, 0x6F, 0x04), times = 10)

        val e = assertThrows<ScsiCommandException> { drive.read(300, 1, ByteArray(2048)) }

        assertEquals(SenseCategory.REGION_MISMATCH, e.sense.category)
        assertEquals(ScsiCommands.READ_10, e.operation)
        assertEquals(1, reads())
    }

    @Test
    fun `scrambled sectors without authentication fail at once`() {
        ready()
        simulator.readFaults[300] = ReadFault(Sense(0x05, 0x6F, 0x03), times = 10)

        val e = assertThrows<ScsiCommandException> { drive.read(300, 1, ByteArray(2048)) }

        assertEquals(SenseCategory.COPY_PROTECTION, e.sense.category)
        assertEquals(1, reads())
    }

    @Test
    fun `a timeout during a read is retried`() {
        ready()
        simulator.timeouts = 1

        drive.read(16, 1, ByteArray(2048))

        assertEquals(1, simulator.resets)
    }

    @Test
    fun `a disconnect during a read is not retried`() {
        ready()
        simulator.disconnected = true

        val e = assertThrows<UsbTransferException> { drive.read(16, 1, ByteArray(2048)) }

        assertEquals(TransferFailure.DISCONNECTED, e.failure)
    }

    @Test
    fun `reads past the end of the disc fail at once`() {
        ready()

        val e = assertThrows<ScsiCommandException> { drive.read(image.size / 2048L, 1, ByteArray(2048)) }

        assertEquals(SenseCategory.LBA_OUT_OF_RANGE, e.sense.category)
        assertEquals(1, reads())
    }

    @Test
    fun `media events`() {
        simulator.insert(MemoryBlockDevice(image))

        assertEquals(MediaEventStatus(MediaEvent.NEW_MEDIA, trayOpen = false, mediaPresent = true), drive.mediaEventStatus())
        assertEquals(MediaEvent.NO_CHANGE, drive.mediaEventStatus()?.event)

        simulator.openTray()
        assertEquals(MediaEventStatus(MediaEvent.MEDIA_REMOVAL, trayOpen = true, mediaPresent = false), drive.mediaEventStatus())
    }

    @Test
    fun `drives without media events are asked only once`() {
        simulator.supportsMediaEvents = false

        assertNull(drive.mediaEventStatus())
        assertNull(drive.mediaEventStatus())

        assertFalse(drive.supportsMediaEvents)
        assertEquals(1, simulator.count(ScsiCommands.GET_EVENT_STATUS_NOTIFICATION))
    }

    @Test
    fun `current profile`() {
        ready()
        assertEquals(DiscProfile(0x0010), drive.currentProfile())

        simulator.supportsConfiguration = false
        assertNull(drive.currentProfile())
    }

    @Test
    fun `eject unlocks the tray first`() {
        ready()
        drive.setRemovalPrevented(true)
        assertTrue(simulator.locked)

        drive.eject()

        assertTrue(simulator.trayOpen)
        assertFalse(simulator.locked)
        val opcodes = simulator.commands.takeLast(2).map { it[0].toInt() and 0xFF }
        assertEquals(listOf(ScsiCommands.PREVENT_ALLOW_MEDIUM_REMOVAL, ScsiCommands.START_STOP_UNIT), opcodes)
    }

    @Test
    fun `load closes the tray`() {
        ready()
        drive.eject()

        drive.load()

        assertFalse(simulator.trayOpen)
    }

    @Test
    fun `copyright information and region state`() {
        ready()
        simulator.copyProtection = 1
        simulator.discRegionMask = 0xFD

        assertEquals(CopyrightInfo(1, 0xFD), drive.copyrightInfo())
        assertEquals(RpcState(typeCode = 1, vendorResetsLeft = 4, userChangesLeft = 4, regionMask = 0xFD, scheme = 1), drive.rpcState())
    }

    @Test
    fun `unsupported informational commands give null`() {
        ready()
        simulator.supportsDvdStructure = false
        simulator.supportsReportKey = false

        assertNull(drive.copyrightInfo())
        assertNull(drive.rpcState())
    }

    @Test
    fun `informational commands still report a missing disc`() {
        ready()
        simulator.removeDisc()

        val e = assertThrows<ScsiCommandException> { drive.copyrightInfo() }

        assertEquals(SenseCategory.NO_MEDIUM, e.sense.category)
        assertNotNull(drive.rpcState())
    }

    @Test
    fun `closing the drive closes the transport`() {
        drive.close()

        assertTrue(simulator.closed)
    }
}
