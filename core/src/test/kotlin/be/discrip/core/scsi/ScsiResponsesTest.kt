package be.discrip.core.scsi

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScsiResponsesTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `inquiry data`() {
        val data = ByteArray(36)
        data[0] = 0x05
        data[1] = 0x80.toByte()
        "ASUS    ".toByteArray().copyInto(data, 8)
        "SDRW-08U9M-U    ".toByteArray().copyInto(data, 16)
        "A112".toByteArray().copyInto(data, 32)

        val inquiry = assertNotNull(InquiryData.parse(data, 36))

        assertTrue(inquiry.isOpticalDrive)
        assertTrue(inquiry.removable)
        assertEquals("ASUS", inquiry.vendor)
        assertEquals("SDRW-08U9M-U", inquiry.product)
        assertEquals("A112", inquiry.revision)
        assertEquals("ASUS SDRW-08U9M-U", inquiry.displayName)
    }

    @Test
    fun `inquiry of a disk is not an optical drive`() {
        val inquiry = assertNotNull(InquiryData.parse(bytes(0x00, 0x80, 0, 0, 0), 5))

        assertFalse(inquiry.isOpticalDrive)
        assertEquals("", inquiry.vendor)
        assertNull(InquiryData.parse(ByteArray(4), 4))
    }

    @Test
    fun `read capacity data`() {
        val capacity = assertNotNull(Capacity.parse(bytes(0x00, 0x22, 0x7F, 0xFF, 0, 0, 0x08, 0), 8))

        assertEquals(0x227FFF, capacity.lastLba)
        assertEquals(0x228000, capacity.blockCount)
        assertEquals(2048, capacity.blockLength)
        assertNull(Capacity.parse(ByteArray(8), 7))
    }

    @Test
    fun `fixed format sense data`() {
        val data = ByteArray(18)
        data[0] = 0xF0.toByte() // valid bit set
        data[2] = 0x05
        data[12] = 0x6F
        data[13] = 0x04

        assertEquals(SenseData(5, 0x6F, 0x04), SenseData.parse(data, 18))
        assertEquals(SenseData(5, 0, 0), SenseData.parse(data, 8))
    }

    @Test
    fun `descriptor format sense data`() {
        assertEquals(SenseData(2, 0x3A, 0x02), SenseData.parse(bytes(0x72, 0x02, 0x3A, 0x02, 0, 0, 0, 0), 8))
        assertNull(SenseData.parse(bytes(0x72, 0x02, 0x3A), 3))
        assertNull(SenseData.parse(bytes(0x00, 0x02), 2))
    }

    @ParameterizedTest
    @CsvSource(
        "0, 00, 00, NO_ERROR, false",
        "1, 17, 01, NO_ERROR, false",
        "2, 3A, 02, NO_MEDIUM, false",
        "2, 04, 01, BECOMING_READY, true",
        "2, 04, 00, NOT_READY, true",
        "2, 30, 00, INCOMPATIBLE_MEDIUM, false",
        "3, 11, 00, MEDIUM_ERROR, true",
        "3, 30, 00, INCOMPATIBLE_MEDIUM, false",
        "4, 08, 03, HARDWARE_ERROR, true",
        "5, 6F, 04, REGION_MISMATCH, false",
        "5, 6F, 05, REGION_MISMATCH, false",
        "5, 6F, 03, COPY_PROTECTION, false",
        "5, 6F, 01, COPY_PROTECTION, false",
        "5, 21, 00, LBA_OUT_OF_RANGE, false",
        "5, 20, 00, INVALID_COMMAND, false",
        "5, 64, 00, INCOMPATIBLE_MEDIUM, false",
        "5, 53, 02, REMOVAL_PREVENTED, false",
        "5, 24, 00, ILLEGAL_REQUEST, false",
        "6, 28, 00, MEDIUM_CHANGED, true",
        "6, 29, 00, RESET, true",
        "B, 00, 00, ABORTED, true",
        "7, 27, 00, OTHER, false",
    )
    fun `sense categories`(key: String, asc: String, ascq: String, category: SenseCategory, retryable: Boolean) {
        val sense = SenseData(key.toInt(16), asc.toInt(16), ascq.toInt(16))

        assertEquals(category, sense.category)
        assertEquals(retryable, sense.isRetryable)
    }

    @Test
    fun `sense prints like the specs write it`() {
        assertEquals("5/6F/04", SenseData(5, 0x6F, 4).toString())
    }

    @Test
    fun `media event status`() {
        val status = assertNotNull(MediaEventStatus.parse(bytes(0, 6, 0x04, 0x10, 0x02, 0x02, 0, 0), 8))

        assertEquals(MediaEventStatus(MediaEvent.NEW_MEDIA, trayOpen = false, mediaPresent = true), status)
        assertEquals(
            MediaEventStatus(MediaEvent.MEDIA_REMOVAL, trayOpen = true, mediaPresent = false),
            MediaEventStatus.parse(bytes(0, 6, 0x04, 0x10, 0x03, 0x01, 0, 0), 8),
        )
        assertEquals(MediaEvent.EJECT_REQUEST, MediaEventStatus.parse(bytes(0, 6, 0x04, 0x10, 0x01, 0x02, 0, 0), 8)?.event)
    }

    @Test
    fun `media event status without a media descriptor`() {
        assertNull(MediaEventStatus.parse(bytes(0, 2, 0x84, 0x10, 0, 0, 0, 0), 8))
        assertNull(MediaEventStatus.parse(bytes(0, 6, 0x02, 0x10, 0, 0, 0, 0), 8))
        assertNull(MediaEventStatus.parse(bytes(0, 2, 0x04, 0x10), 4))
    }

    @Test
    fun `disc profiles`() {
        assertEquals(DiscType.DVD, DiscProfile(0x0010).type)
        assertEquals("DVD-ROM", DiscProfile(0x0010).name)
        assertEquals(DiscType.DVD, DiscProfile(0x002B).type)
        assertEquals(DiscType.CD, DiscProfile(0x0008).type)
        assertEquals(DiscType.BLU_RAY, DiscProfile(0x0040).type)
        assertEquals(DiscType.HD_DVD, DiscProfile(0x0051).type)
        assertEquals(DiscType.NONE, DiscProfile(0).type)
        assertEquals(DiscType.OTHER, DiscProfile(0x0002).type)
        assertEquals("0x0002", DiscProfile(0x0002).name)
        assertEquals(DiscProfile(0x0010), DiscProfile.parseHeader(bytes(0, 0, 0, 4, 0, 0, 0x00, 0x10), 8))
        assertNull(DiscProfile.parseHeader(ByteArray(6), 6))
    }

    @Test
    fun `copyright information`() {
        val css = assertNotNull(CopyrightInfo.parse(bytes(0, 6, 0, 0, 0x01, 0xFD, 0, 0), 8))

        assertTrue(css.isCss)
        assertEquals(setOf(2), css.playableRegions)

        val free = assertNotNull(CopyrightInfo.parse(bytes(0, 6, 0, 0, 0x00, 0x00, 0, 0), 8))
        assertFalse(free.isCss)
        assertEquals(emptySet(), free.playableRegions)
        assertNull(CopyrightInfo.parse(ByteArray(5), 5))
    }

    @Test
    fun `rpc state`() {
        // Region set (01b), 4 vendor resets, 3 user changes; region 2 only; RPC-II.
        val rpc = assertNotNull(RpcState.parse(bytes(0, 6, 0, 0, 0b01_100_011, 0xFD, 0x01, 0), 8))

        assertEquals(RpcState(typeCode = 1, vendorResetsLeft = 4, userChangesLeft = 3, regionMask = 0xFD, scheme = 1), rpc)
        assertTrue(rpc.enforcesRegion)
        assertTrue(rpc.regionSet)
        assertEquals(setOf(2), rpc.playableRegions)
        assertNull(RpcState.parse(ByteArray(6), 6))
    }

    @Test
    fun `region masks`() {
        assertEquals((1..8).toSet(), playableRegions(0x00))
        assertEquals(setOf(1, 2, 4), playableRegions(0xF4))
        assertEquals(emptySet(), playableRegions(0xFF))
    }
}
