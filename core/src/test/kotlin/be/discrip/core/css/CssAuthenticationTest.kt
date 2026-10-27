package be.discrip.core.css

import be.discrip.core.disc.BlockDevice
import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.scsi.ScsiCommandException
import be.discrip.core.scsi.ScsiCommands
import be.discrip.core.scsi.SenseCategory
import be.discrip.core.testing.SimulatedOpticalDrive
import be.discrip.core.usb.BulkOnlyTransport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CssAuthenticationTest {

    private val simulator = SimulatedOpticalDrive().apply {
        spinUpPolls = 0
        copyProtection = SimulatedOpticalDrive.CSS
        discRegionMask = 0xFD // a region 2 disc
        driveRegionMask = 0xFD
    }
    private val drive = OpticalDrive(BulkOnlyTransport(simulator))
    private val buffer = ByteArray(OpticalDrive.BLOCK_SIZE)

    @BeforeEach
    fun insertDisc() {
        simulator.insert(ScrambledDisc())
        // The unit attention after the disc went in.
        drive.testUnitReady()
    }

    @Test
    fun `scrambled sectors are refused until the drive is unlocked`() {
        drive.read(ScrambledDisc.CLEAR, 1, buffer)
        val refused = assertThrows<ScsiCommandException> { drive.read(ScrambledDisc.SCRAMBLED, 1, buffer) }
        assertEquals(SenseCategory.COPY_PROTECTION, refused.sense.category)

        CssAuthentication.unlock(drive)

        assertTrue(simulator.authenticationSucceeded)
        drive.read(ScrambledDisc.SCRAMBLED, 1, buffer)
        assertEquals(0xBA, buffer[3].toInt() and 0xFF)
        // The whole exchange, in libdvdcss's order.
        assertEquals(
            listOf("A4/00", "A3/01", "A4/02", "A4/01", "A3/03", "AD/02", "A4/05"),
            simulator.commands.filter { it[0].toInt() and 0xFF in setOf(0xA3, 0xA4, 0xAD) }.map(::describe),
        )
    }

    @Test
    fun `every cipher variant is recognised`() {
        for (variant in listOf(0, 7, 31)) {
            simulator.cssVariant = variant
            simulator.insert(ScrambledDisc())
            drive.testUnitReady()
            CssAuthentication.unlock(drive)
            assertTrue(simulator.authenticationSucceeded, "variant $variant")
        }
    }

    @Test
    fun `grant ids left by crashed sessions are freed`() {
        simulator.hangAllAgids()

        CssAuthentication.unlock(drive)

        assertTrue(simulator.authenticationSucceeded)
        assertEquals(1, simulator.commands.count { describe(it) == "A4/3F" })
    }

    @Test
    fun `a drive of another region is named as such`() {
        simulator.driveRegionMask = 0xFE // region 1
        simulator.insert(ScrambledDisc())
        drive.testUnitReady()

        val failure = assertThrows<CssAuthenticationException> { CssAuthentication.unlock(drive) }

        assertTrue(failure.regionMismatch)
        val refused = assertThrows<ScsiCommandException> { drive.read(ScrambledDisc.SCRAMBLED, 1, buffer) }
        assertEquals(SenseCategory.REGION_MISMATCH, refused.sense.category)
    }

    @Test
    fun `a drive that reports the region error itself is named as such too`() {
        simulator.driveRegionMask = 0xFE
        simulator.regionErrorOnDiscKey = true

        assertTrue(assertThrows<CssAuthenticationException> { CssAuthentication.unlock(drive) }.regionMismatch)
    }

    @Test
    fun `a drive whose region was never set refuses as well`() {
        simulator.rpcType = 0

        assertTrue(assertThrows<CssAuthenticationException> { CssAuthentication.unlock(drive) }.regionMismatch)
    }

    @Test
    fun `a failed key exchange frees its grant id and says so`() {
        simulator.rejectKey2 = true

        val failure = assertThrows<CssAuthenticationException> { CssAuthentication.unlock(drive) }

        assertFalse(failure.regionMismatch)
        assertFalse(simulator.authenticationSucceeded)
        // The next attempt gets a grant id again.
        simulator.rejectKey2 = false
        CssAuthentication.unlock(drive)
        assertTrue(simulator.authenticationSucceeded)
    }

    @Test
    fun `a drive without CSS support is named as such`() {
        simulator.supportsReportKey = false

        val failure = assertThrows<CssAuthenticationException> { CssAuthentication.unlock(drive) }

        assertEquals("The drive does not support CSS authentication", failure.message)
    }

    @Test
    fun `a new disc needs a new authentication`() {
        CssAuthentication.unlock(drive)
        simulator.insert(ScrambledDisc())
        drive.testUnitReady()

        assertThrows<ScsiCommandException> { drive.read(ScrambledDisc.SCRAMBLED, 1, buffer) }
    }

    private fun describe(cdb: ByteArray): String {
        val opcode = cdb[0].toInt() and 0xFF
        if (cdb.size < 12) return "%02X".format(opcode)
        val format = if (opcode == ScsiCommands.READ_DVD_STRUCTURE) cdb[7].toInt() and 0xFF else cdb[10].toInt() and 0x3F
        return "%02X/%02X".format(opcode, format)
    }

    /** 64 blocks; block [SCRAMBLED] is a video pack marked scrambled, block [CLEAR] a navigation pack. */
    private class ScrambledDisc : BlockDevice {
        override val blockCount = 64L

        override fun read(lba: Long, blocks: Int, buffer: ByteArray, offset: Int) {
            buffer.fill(0, offset, offset + blocks * BlockDevice.BLOCK_SIZE)
            for (i in 0 until blocks) {
                val at = offset + i * BlockDevice.BLOCK_SIZE
                if (lba + i == SCRAMBLED || lba + i == CLEAR) {
                    byteArrayOf(0, 0, 1, 0xBA.toByte()).copyInto(buffer, at)
                    byteArrayOf(0, 0, 1, if (lba + i == CLEAR) 0xBB.toByte() else 0xE0.toByte()).copyInto(buffer, at + 0x0E)
                    buffer[at + 0x14] = if (lba + i == CLEAR) 0 else 0x90.toByte()
                }
            }
        }

        override fun close() = Unit

        companion object {
            const val CLEAR = 20L
            const val SCRAMBLED = 21L
        }
    }
}
