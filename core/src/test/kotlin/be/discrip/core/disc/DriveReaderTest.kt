package be.discrip.core.disc

import be.discrip.core.css.CssAuthenticationException
import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.scsi.ScsiCommands
import be.discrip.core.testing.MemoryBlockDevice
import be.discrip.core.testing.SimulatedOpticalDrive
import be.discrip.core.testing.SimulatedOpticalDrive.ReadFault
import be.discrip.core.testing.SimulatedOpticalDrive.Sense
import be.discrip.core.testing.TestImages
import be.discrip.core.usb.BulkOnlyTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveReaderTest {

    private val simulator = SimulatedOpticalDrive().apply { spinUpPolls = 0 }
    private val drive = OpticalDrive(BulkOnlyTransport(simulator))
    private val movie = MemoryBlockDevice(scrambledMovie("MOVIE"))
    private val buffer = ByteArray(8 * OpticalDrive.BLOCK_SIZE)

    private fun open(): DriveReader {
        simulator.insert(movie)
        // The unit attention of the new disc, as the drive monitor would have taken it.
        drive.testUnitReady()
        return DriveReader.open(drive, movie.blockCount, sleep = {})
    }

    private fun css() {
        simulator.copyProtection = SimulatedOpticalDrive.CSS
        simulator.discRegionMask = 0xFD
        simulator.driveRegionMask = 0xFD
    }

    private fun reads() = simulator.count(ScsiCommands.READ_10)

    @Test
    fun `reads blocks, fewer at the end of the disc and none past it`() {
        val reader = open()

        assertEquals(4, reader.read(16, 4, buffer))
        assertEquals(1, buffer[0].toInt())
        assertEquals(2, reader.read(movie.blockCount - 2, 4, buffer))
        assertEquals(0, reader.read(movie.blockCount, 4, buffer))
        assertNull(reader.problem)
    }

    @Test
    fun `a bad block fails that read only, after one attempt`() {
        val reader = open()
        simulator.readFaults[40] = ReadFault(Sense(0x03, 0x11, 0x00), times = 5)
        val before = reads()

        assertEquals(DriveReader.FAILED, reader.read(40, 1, buffer))
        assertEquals(1, reads() - before)
        assertNull(reader.problem)
        assertEquals(1, reader.read(41, 1, buffer))
    }

    @Test
    fun `a CSS disc is read once the drive is unlocked`() {
        css()
        val reader = open()

        reader.unlock()

        assertEquals(1, reader.read(SCRAMBLED, 1, buffer))
        assertTrue(simulator.authenticationSucceeded)
    }

    @Test
    fun `the reader authenticates by itself when the drive refuses a scrambled block`() {
        css()
        val reader = open()

        assertEquals(2, reader.read(SCRAMBLED, 2, buffer))
        assertTrue(simulator.authenticationSucceeded)
    }

    @Test
    fun `authentication lost to a bus reset is restored`() {
        css()
        val reader = open()
        reader.unlock()
        simulator.busReset()

        assertEquals(1, reader.read(SCRAMBLED, 1, buffer))
        assertNull(reader.problem)
    }

    @Test
    fun `a drive that refuses the disc's region ends reading`() {
        css()
        simulator.driveRegionMask = 0xFE
        val reader = open()

        assertTrue(assertThrows<CssAuthenticationException> { reader.unlock() }.regionMismatch)
        assertEquals(DriveProblem.REGION_MISMATCH, reader.problem)
        assertEquals(DriveReader.FAILED, reader.read(16, 1, buffer))
    }

    @Test
    fun `a region refusal while reading ends reading too`() {
        css()
        simulator.driveRegionMask = 0xFE
        val reader = open()

        assertEquals(DriveReader.FAILED, reader.read(SCRAMBLED, 1, buffer))
        assertEquals(DriveProblem.REGION_MISMATCH, reader.problem)
    }

    @Test
    fun `a drive that will not authenticate ends reading`() {
        css()
        simulator.rejectKey2 = true
        val reader = open()

        assertEquals(DriveReader.FAILED, reader.read(SCRAMBLED, 1, buffer))
        assertEquals(DriveProblem.CSS_FAILED, reader.problem)
    }

    @Test
    fun `a disc taken out ends reading`() {
        val reader = open()
        simulator.removeDisc()

        assertEquals(DriveReader.FAILED, reader.read(16, 1, buffer))
        assertEquals(DriveProblem.DISC_CHANGED, reader.problem)
    }

    @Test
    fun `another disc ends reading`() {
        val reader = open()
        simulator.insert(MemoryBlockDevice(scrambledMovie("OTHER")))

        assertEquals(DriveReader.FAILED, reader.read(16, 1, buffer))
        assertEquals(DriveProblem.DISC_CHANGED, reader.problem)
    }

    @Test
    fun `the same disc put back in is read on`() {
        css()
        val reader = open()
        reader.unlock()
        simulator.removeDisc()
        simulator.insert(movie)

        assertEquals(1, reader.read(SCRAMBLED, 1, buffer))
        assertNull(reader.problem)
    }

    @Test
    fun `a drive that is spinning up again is waited for`() {
        val reader = open()
        simulator.readFaults[40] = ReadFault(Sense(0x02, 0x04, 0x01), times = 1)

        assertEquals(1, reader.read(40, 1, buffer))
        assertNull(reader.problem)
    }

    @Test
    fun `an unplugged drive ends reading`() {
        val reader = open()
        simulator.disconnected = true

        assertEquals(DriveReader.FAILED, reader.read(16, 1, buffer))
        assertEquals(DriveProblem.DISCONNECTED, reader.problem)
    }

    @Test
    fun `a timed-out read of a drive that still answers is only that read failing`() {
        val reader = open()
        simulator.timeouts = 1

        assertEquals(DriveReader.FAILED, reader.read(40, 1, buffer))
        assertNull(reader.problem)
        assertEquals(1, reader.read(40, 1, buffer))
    }

    @Test
    fun `a drive that stops answering ends reading`() {
        val reader = open()
        simulator.timeouts = 100

        assertEquals(DriveReader.FAILED, reader.read(40, 1, buffer))
        assertEquals(DriveProblem.DISCONNECTED, reader.problem)
    }

    private companion object {
        /** Blocks 330 to 339 of the movies are video packs marked scrambled. */
        const val SCRAMBLED = 330L

        fun scrambledMovie(label: String): ByteArray {
            val image = TestImages.synthetic(isoLabel = label, udfLabel = label)
            for (block in SCRAMBLED until SCRAMBLED + 10) {
                val at = (block * OpticalDrive.BLOCK_SIZE).toInt()
                byteArrayOf(0, 0, 1, 0xBA.toByte()).copyInto(image, at)
                byteArrayOf(0, 0, 1, 0xE0.toByte()).copyInto(image, at + 0x0E)
                image[at + 0x14] = 0x90.toByte()
            }
            return image
        }
    }
}
