package be.discrip.rip

import androidx.test.ext.junit.runners.AndroidJUnit4
import be.discrip.core.disc.DiscInfo
import be.discrip.core.disc.DiscProbe
import be.discrip.core.disc.DriveProblem
import be.discrip.core.disc.DriveReader
import be.discrip.core.disc.FileBlockDevice
import be.discrip.core.dvd.RipFailure
import be.discrip.core.history.RipResult
import be.discrip.core.model.MovieMetadata
import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.scsi.ScsiCommands
import be.discrip.core.testing.CssScrambler
import be.discrip.core.testing.SimulatedOpticalDrive
import be.discrip.core.usb.BulkOnlyTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * Rips from a USB drive, simulated down to its Bulk-Only Transport: the app's SCSI layer, CSS
 * authentication and drive reader under the real engine, which reads the disc through JNI. The
 * disc is the test DVD, CSS-scrambled where the test says so with libdvdcss's own cipher, so the
 * engine has to recover the title key from the sectors as on a protected disc.
 */
@RunWith(AndroidJUnit4::class)
class DriveEndToEndTest {

    private val rig = RipTestRig("drive-e2e")
    private val simulator = SimulatedOpticalDrive()
    private val drive = OpticalDrive(BulkOnlyTransport(simulator))

    @After
    fun tearDown() = rig.close()

    @Test
    fun aCssProtectedDiscRipsLikeTheClearImageAndComesOut() = runBlocking {
        // The same film twice, a year apart so the two files do not collide.
        val clear = rig.ripAndWait(rig.job(rig.openImage(), metadata = MovieMetadata(title = "Testfilm", year = 2023)))
        assertTrue(clear.toString(), clear is RipState.Succeeded)

        val disc = insert(scrambledCopy(), css = true)
        val source = DvdSource.Drive(INSERTION)
        val ready = rig.open(source)
        assertTrue("the drive should have authenticated", simulator.authenticationSucceeded)
        assertEquals("DISCRIP_TEST_DVD", ready.name)

        val state = rig.ripAndWait(rig.job(ready, source = source, metadata = MovieMetadata(title = "Testfilm", year = 2024)))

        assertTrue(state.toString(), state is RipState.Succeeded)
        state as RipState.Succeeded
        assertTrue("the disc should have come out", state.discEjected)
        assertTrue(simulator.trayOpen)
        assertFalse(simulator.locked)
        // Locked for the rip, unlocked after it and again to eject.
        assertTrue(simulator.count(ScsiCommands.PREVENT_ALLOW_MEDIUM_REMOVAL) >= 3)
        assertTrue("the drive should be given back", disc.closed)
        assertEquals(SourceState.None, rig.manager.source.value)

        // Decrypted right, the two files differ only in the year, the muxing date, the random
        // segment id and the checksums over them; with a wrong key every packet would differ.
        val files = rig.filesIn(rig.out).associateBy { it.name }
        val fromImage = files.getValue("Testfilm (2023).mkv").readBytes()
        val fromDrive = files.getValue("Testfilm (2024).mkv").readBytes()
        assertEquals(fromImage.size, fromDrive.size)
        val differing = fromImage.indices.count { fromImage[it] != fromDrive[it] }
        assertTrue("$differing bytes differ", differing < 64)
        assertEquals(RipResult.SUCCEEDED, rig.history.load().first().result)
    }

    @Test
    fun aDriveUnpluggedHalfwayStopsTheRipWithThatReason() = runBlocking {
        val disc = insert(clearCopy())
        val source = DvdSource.Drive(INSERTION)
        val ready = rig.open(source)
        val opened = disc.reads
        disc.beforeRead = { reads -> if (reads == opened + 10) simulator.disconnected = true }

        val state = rig.ripAndWait(rig.job(ready, source = source))

        assertTrue(state.toString(), state is RipState.Failed)
        assertEquals(RipFailure.DRIVE_DISCONNECTED, (state as RipState.Failed).reason)
        assertEquals(DriveProblem.DISCONNECTED, disc.problem)
        assertEquals(emptyList<File>(), rig.filesIn(rig.out))
        assertTrue(disc.closed)
        assertFalse(disc.ejected)
        assertEquals(RipFailure.DRIVE_DISCONNECTED, rig.history.load().first().failure)
    }

    @Test
    fun aDiscTakenOutHalfwayStopsTheRipWithThatReason() = runBlocking {
        val disc = insert(clearCopy())
        val source = DvdSource.Drive(INSERTION)
        val ready = rig.open(source)
        val opened = disc.reads
        disc.beforeRead = { reads -> if (reads == opened + 10) simulator.removeDisc() }

        val state = rig.ripAndWait(rig.job(ready, source = source))

        assertTrue(state.toString(), state is RipState.Failed)
        assertEquals(RipFailure.DISC_CHANGED, (state as RipState.Failed).reason)
        assertEquals(emptyList<File>(), rig.filesIn(rig.out))
        assertTrue(disc.closed)
    }

    @Test
    fun aDriveOfAnotherRegionIsRefusedWhenTheDiscOpens() = runBlocking {
        val disc = insert(scrambledCopy(), css = true, driveRegionMask = 0xFE)

        val state = rig.openUntilSettled(DvdSource.Drive(INSERTION))

        assertTrue(state.toString(), state is SourceState.Failed)
        assertEquals(SourceProblem.REGION_MISMATCH, (state as SourceState.Failed).problem)
        assertTrue("the drive should be given back", disc.closed)
    }

    @Test
    fun aDiscNoLongerInTheDriveIsSaidToBeGone() = runBlocking {
        insert(clearCopy())

        val state = rig.openUntilSettled(DvdSource.Drive(INSERTION + 1))

        assertTrue(state.toString(), state is SourceState.Failed)
        assertEquals(SourceProblem.DISC_GONE, (state as SourceState.Failed).problem)
    }

    /** Puts [image] in the simulated drive, region 2, and has the rig lend it as insertion [INSERTION]. */
    private fun insert(image: File, css: Boolean = false, driveRegionMask: Int = 0xFD): TestDriveDisc {
        simulator.copyProtection = if (css) SimulatedOpticalDrive.CSS else 0
        simulator.discRegionMask = 0xFD
        simulator.driveRegionMask = driveRegionMask
        simulator.insert(FileBlockDevice.open(image))
        // What the drive monitor does before probing: wait out the unit attention and the spin-up.
        repeat(10) { if (drive.testUnitReady() == null) return@repeat }
        val disc = TestDriveDisc(drive, DiscProbe.probe(drive))
        rig.lend = { insertion -> disc.takeIf { insertion == INSERTION } }
        return disc
    }

    private fun clearCopy(): File = File(rig.work, "drive.iso").also { File(rig.work, "test-dvd.iso").copyTo(it, overwrite = true) }

    /** The test DVD with its audio and video packs scrambled, as on a CSS-protected disc. */
    private fun scrambledCopy(): File {
        val copy = clearCopy()
        val scrambled = CssScrambler.scrambleImage(copy, byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte()))
        assertTrue(scrambled > 1000)
        return copy
    }

    /** The disc in the simulated drive, lent as DriveManager lends a real one (minus its drive thread). */
    private class TestDriveDisc(private val drive: OpticalDrive, override val info: DiscInfo) : DriveDisc {
        private val reader = DriveReader.open(drive, info.blockCount, sleep = {})

        /** Reads so far, opening the disc included. */
        var reads = 0
            private set

        /** Called before every read with its number, to pull the plug or the disc halfway. */
        var beforeRead: (Int) -> Unit = {}
        var closed = false
        var ejected = false

        override val problem: DriveProblem? get() = reader.problem

        override fun read(lba: Long, count: Int, buffer: ByteArray): Int {
            beforeRead(++reads)
            return reader.read(lba, count, buffer)
        }

        override fun unlock() = reader.unlock()

        override fun preventRemoval(prevent: Boolean) {
            try {
                drive.setRemovalPrevented(prevent)
            } catch (e: IOException) {
                // As the app: not every drive can.
            }
        }

        override fun eject(): Boolean = try {
            drive.eject()
            ejected = true
            true
        } catch (e: IOException) {
            false
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val INSERTION = 1L
    }
}
