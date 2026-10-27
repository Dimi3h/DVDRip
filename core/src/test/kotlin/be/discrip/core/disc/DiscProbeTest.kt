package be.discrip.core.disc

import be.discrip.core.scsi.CopyrightInfo
import be.discrip.core.scsi.DiscProfile
import be.discrip.core.scsi.OpticalDrive
import be.discrip.core.scsi.RpcState
import be.discrip.core.testing.MemoryBlockDevice
import be.discrip.core.testing.SimulatedOpticalDrive
import be.discrip.core.testing.TestImages
import be.discrip.core.usb.BulkOnlyTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscProbeTest {

    private val simulator = SimulatedOpticalDrive().apply { spinUpPolls = 0 }
    private val drive = OpticalDrive(BulkOnlyTransport(simulator))

    private fun probe(image: ByteArray = TestImages.synthetic(isoLabel = "MOVIE", udfLabel = "Movie")): DiscInfo {
        simulator.insert(MemoryBlockDevice(image))
        while (drive.testUnitReady() != null) Unit
        return DiscProbe.probe(drive)
    }

    private fun cssDisc(regionMask: Int) {
        simulator.copyProtection = 1
        simulator.discRegionMask = regionMask
    }

    @Test
    fun `an unprotected dvd-video disc`() {
        val disc = probe()

        assertEquals("Movie", disc.label)
        assertTrue(disc.isDvdVideo)
        assertEquals(400, disc.blockCount)
        assertEquals(400L * 2048, disc.sizeBytes)
        assertEquals(DiscProfile(0x0010), disc.profile)
        assertEquals(false, disc.cssProtected)
        assertEquals(RegionVerdict.NOT_ENFORCED, disc.region?.verdict)
    }

    @Test
    fun `a data dvd is readable but not dvd-video`() {
        val disc = probe(TestImages.fixture("data.iso"))

        assertEquals("DATA_DISC", disc.label)
        assertFalse(disc.isDvdVideo)
    }

    @Test
    fun `a css disc for the drive's region`() {
        cssDisc(regionMask = 0xFD)

        val disc = probe()

        assertEquals(true, disc.cssProtected)
        assertEquals(RegionStatus(setOf(2), setOf(2), 4, RegionVerdict.MATCH), disc.region)
    }

    @Test
    fun `a css disc for another region`() {
        cssDisc(regionMask = 0xFE)

        assertEquals(RegionVerdict.MISMATCH, probe().region?.verdict)
    }

    @Test
    fun `a region-free css disc plays everywhere`() {
        cssDisc(regionMask = 0x00)
        simulator.driveRegionMask = 0xFB

        assertEquals(RegionVerdict.MATCH, probe().region?.verdict)
    }

    @Test
    fun `an rpc-ii drive whose region was never set`() {
        cssDisc(regionMask = 0xFD)
        simulator.rpcType = 0
        simulator.driveRegionMask = 0xFF

        val region = probe().region

        assertEquals(RegionVerdict.DRIVE_REGION_NOT_SET, region?.verdict)
        assertNull(region?.driveRegions)
    }

    @Test
    fun `an rpc-i drive does not enforce regions`() {
        cssDisc(regionMask = 0xFE)
        simulator.rpcScheme = 0

        assertEquals(RegionVerdict.NOT_ENFORCED, probe().region?.verdict)
    }

    @Test
    fun `a drive that keeps its region to itself`() {
        cssDisc(regionMask = 0xFD)
        simulator.supportsReportKey = false

        assertEquals(RegionVerdict.UNKNOWN, probe().region?.verdict)
    }

    @Test
    fun `a drive without copyright information`() {
        simulator.supportsDvdStructure = false

        val disc = probe()

        assertNull(disc.cssProtected)
        assertEquals(RegionVerdict.UNKNOWN, disc.region?.verdict)
    }

    @Test
    fun `a cd is refused`() {
        simulator.profile = 0x0008

        val e = assertThrows<DiscProbeException> { probe() }

        assertEquals(DiscProblem.WrongMedium(DiscProfile(0x0008)), e.problem)
    }

    @Test
    fun `a blu-ray is refused`() {
        simulator.profile = 0x0040

        assertEquals(DiscProblem.WrongMedium(DiscProfile(0x0040)), assertThrows<DiscProbeException> { probe() }.problem)
    }

    @Test
    fun `a blank disc`() {
        simulator.profile = 0x0011

        assertEquals(DiscProblem.Blank, assertThrows<DiscProbeException> { probe(ByteArray(2048)) }.problem)
    }

    @Test
    fun `an image has no drive information`() {
        val disc = DiscProbe.probe(MemoryBlockDevice(TestImages.fixture("bridge.iso")))

        assertEquals("DISCRIP_TEST", disc.label)
        assertTrue(disc.isDvdVideo)
        assertNull(disc.profile)
        assertNull(disc.cssProtected)
        assertNull(disc.region)
    }

    @Test
    fun `region verdicts`() {
        val region2Drive = RpcState(typeCode = 1, vendorResetsLeft = 4, userChangesLeft = 5, regionMask = 0xFD, scheme = 1)

        assertEquals(RegionVerdict.UNKNOWN, RegionStatus.evaluate(null, region2Drive).verdict)
        assertEquals(RegionVerdict.NOT_ENFORCED, RegionStatus.evaluate(CopyrightInfo(0, 0xFE), region2Drive).verdict)
        assertEquals(RegionVerdict.MATCH, RegionStatus.evaluate(CopyrightInfo(1, 0xF5), region2Drive).verdict)
        assertEquals(RegionVerdict.MISMATCH, RegionStatus.evaluate(CopyrightInfo(1, 0xFF), region2Drive).verdict)
        assertEquals(5, RegionStatus.evaluate(CopyrightInfo(1, 0xF5), region2Drive).changesLeft)
        assertEquals(setOf(2, 4), RegionStatus.evaluate(CopyrightInfo(1, 0xF5), region2Drive).discRegions)
    }
}
