package be.discrip.rip

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeFolderTest {

    private val tree = object : DvdTree {
        val files = mapOf(listOf("VIDEO_TS", "VIDEO_TS.IFO") to 6144L, listOf("VIDEO_TS", "VTS_01_1.VOB") to 1_073_709_056L)

        override fun stat(path: List<String>): DvdTree.Entry? = when {
            path.isEmpty() || path == listOf("VIDEO_TS") -> DvdTree.Entry.Directory
            path == listOf("BROKEN") -> throw SecurityException("access withdrawn")
            else -> files[path]?.let { DvdTree.Entry.File(it) }
        }

        override fun list(path: List<String>): List<String>? = when (path) {
            emptyList<String>() -> listOf("VIDEO_TS", "Ä-Extra")
            listOf("VIDEO_TS") -> files.keys.map { it.last() }
            else -> null
        }

        override fun open(path: List<String>): ParcelFileDescriptor? = throw IllegalStateException("provider gone")
    }

    private val folder = NativeFolder(tree)

    @Test
    fun `paths from libdvdread are split into names below the picked folder`() {
        assertEquals(listOf("VIDEO_TS", "VTS_01_1.VOB"), NativeFolder.segments("//VIDEO_TS/VTS_01_1.VOB".encodeToByteArray()))
        assertEquals(listOf("VIDEO_TS"), NativeFolder.segments("/./VIDEO_TS/".encodeToByteArray()))
        assertEquals(emptyList<String>(), NativeFolder.segments("/".encodeToByteArray()))
    }

    @Test
    fun `stat reports sizes, directories and missing files`() {
        assertEquals(1_073_709_056L, folder.stat("//VIDEO_TS/VTS_01_1.VOB".encodeToByteArray()))
        assertEquals(NativeFolder.DIRECTORY, folder.stat("/".encodeToByteArray()))
        assertEquals(NativeFolder.MISSING, folder.stat("/VIDEO_TS/VTS_09_1.VOB".encodeToByteArray()))
    }

    @Test
    fun `names travel as UTF-8`() {
        val names = folder.list("/".encodeToByteArray())!!.map { it.decodeToString() }
        assertEquals(listOf("VIDEO_TS", "Ä-Extra"), names)
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0x84.toByte()), folder.list("/".encodeToByteArray())!![1].copyOf(2))
        assertNull(folder.list("/AUDIO_TS".encodeToByteArray()))
    }

    @Test
    fun `failures never reach native code`() {
        assertEquals(NativeFolder.MISSING, folder.stat("/BROKEN".encodeToByteArray()))
        assertEquals(-1, folder.open("/VIDEO_TS/VIDEO_TS.IFO".encodeToByteArray()))
    }
}
