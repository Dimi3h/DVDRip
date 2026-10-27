package be.discrip.core.disc

import be.discrip.core.testing.MemoryBlockDevice
import be.discrip.core.testing.TestImages
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumeReaderTest {

    private fun read(image: ByteArray) = VolumeReader.read(MemoryBlockDevice(image))

    @Test
    fun `udf bridge image made by genisoimage`() {
        val volume = read(TestImages.fixture("bridge.iso"))

        assertEquals("DISCRIP_TEST", volume.label)
        assertEquals("DISCRIP_TEST", volume.isoLabel)
        assertEquals("DISCRIP_TEST", volume.udfLabel)
        assertEquals(setOf(FileSystemType.ISO9660, FileSystemType.UDF), volume.fileSystems)
        assertTrue(volume.hasRootDirectory("VIDEO_TS"))
        assertTrue(volume.hasRootDirectory("audio_ts"))
    }

    @Test
    fun `iso 9660 data disc made by genisoimage`() {
        val volume = read(TestImages.fixture("data.iso"))

        assertEquals("DATA_DISC", volume.label)
        assertNull(volume.udfLabel)
        assertEquals(setOf(FileSystemType.ISO9660), volume.fileSystems)
        assertEquals(listOf("DOCS"), volume.rootDirectories)
        assertFalse(volume.hasRootDirectory("VIDEO_TS"))
    }

    @Test
    fun `udf-only image with a unicode label made by mkudffs`() {
        val volume = read(TestImages.fixture("udf-only.img"))

        assertEquals("Amélie 漢字", volume.label)
        assertNull(volume.isoLabel)
        assertEquals(setOf(FileSystemType.UDF), volume.fileSystems)
        assertEquals(emptyList(), volume.rootDirectories)
    }

    @Test
    fun `udf label wins over the iso 9660 label`() {
        val volume = read(TestImages.synthetic(isoLabel = "THE_MATRIX", udfLabel = "The Matrix"))

        assertEquals("The Matrix", volume.label)
        assertEquals("THE_MATRIX", volume.isoLabel)
    }

    @Test
    fun `iso 9660 label when the udf tree has none`() {
        val volume = read(TestImages.synthetic(isoLabel = "ONLY_ISO", udfLabel = null))

        assertEquals("ONLY_ISO", volume.label)
        assertEquals(setOf(FileSystemType.ISO9660), volume.fileSystems)
        assertTrue(volume.hasRootDirectory("VIDEO_TS"))
    }

    @Test
    fun `video_ts is found in a udf-only disc`() {
        val volume = read(TestImages.synthetic(isoLabel = null, udfLabel = "Home Movies"))

        assertEquals("Home Movies", volume.label)
        assertEquals(setOf(FileSystemType.UDF), volume.fileSystems)
        assertEquals(listOf("AUDIO_TS", "VIDEO_TS"), volume.rootDirectories)
    }

    @Test
    fun `the reserve descriptor sequence stands in for a damaged main one`() {
        val volume = read(TestImages.synthetic(isoLabel = null, udfLabel = "Reserve", breakMainSequence = true))

        assertEquals("Reserve", volume.label)
        assertTrue(volume.hasRootDirectory("VIDEO_TS"))
    }

    @Test
    fun `a disc without video_ts`() {
        val volume = read(TestImages.synthetic(directories = listOf("PHOTOS")))

        assertFalse(volume.hasRootDirectory("VIDEO_TS"))
        assertEquals(listOf("PHOTOS"), volume.rootDirectories)
    }

    @Test
    fun `an unformatted image has nothing to say`() {
        assertEquals(VolumeInfo.EMPTY, read(ByteArray(300 * 2048)))
    }

    @Test
    fun `a udf tree without an anchor is ignored`() {
        val volume = read(TestImages.synthetic(isoLabel = "BRIDGE", udfLabel = "Lost", anchor = false))

        assertEquals("BRIDGE", volume.label)
        assertNull(volume.udfLabel)
        assertEquals(setOf(FileSystemType.ISO9660), volume.fileSystems)
        assertTrue(volume.hasRootDirectory("VIDEO_TS"))
    }

    @Test
    fun `dstrings in both compressions`() {
        val field = ByteArray(32)
        field[0] = 16
        "Zoë".toByteArray(Charsets.UTF_16BE).copyInto(field, 1)
        field[31] = 7

        assertEquals("Zoë", VolumeReader.dString(field, 0, 32))

        field[0] = 8
        "TITLE   ".toByteArray().copyInto(field, 1)
        field[31] = 9
        assertEquals("TITLE", VolumeReader.dString(field, 0, 32))
    }

    @Test
    fun `invalid dstrings are ignored`() {
        val field = ByteArray(32)
        assertNull(VolumeReader.dString(field, 0, 32))

        field[0] = 8
        field[31] = 32
        assertNull(VolumeReader.dString(field, 0, 32))

        field[0] = 3
        field[31] = 5
        assertNull(VolumeReader.dString(field, 0, 32))
    }
}
