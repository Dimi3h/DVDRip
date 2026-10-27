package be.discrip.rip

import android.os.ParcelFileDescriptor

/** A folder holding a DVD's VIDEO_TS files, such as one picked with the Storage Access Framework. */
interface DvdTree {

    sealed interface Entry {
        data object Directory : Entry
        data class File(val size: Long) : Entry
    }

    /** The entry at [path] (names below the picked folder; empty for the folder itself), or null. */
    fun stat(path: List<String>): Entry?

    /** Names in the directory at [path], or null when it is not a readable directory. */
    fun list(path: List<String>): List<String>?

    /** The file at [path] opened for reading, or null. */
    fun open(path: List<String>): ParcelFileDescriptor?
}

/**
 * What native code calls for a VIDEO_TS folder (see `folder_filesystem` in rip_jni.c): paths
 * arrive as UTF-8 such as `//VIDEO_TS/VTS_01_1.VOB`, rooted at the picked folder. Exceptions
 * never cross into native code; a failure is reported as "missing".
 */
internal class NativeFolder(private val tree: DvdTree) {

    fun stat(path: ByteArray): Long = try {
        when (val entry = tree.stat(segments(path))) {
            null -> MISSING
            DvdTree.Entry.Directory -> DIRECTORY
            is DvdTree.Entry.File -> entry.size
        }
    } catch (e: Exception) {
        MISSING
    }

    fun list(path: ByteArray): Array<ByteArray>? = try {
        tree.list(segments(path))?.map { it.encodeToByteArray() }?.toTypedArray()
    } catch (e: Exception) {
        null
    }

    /** A descriptor native code then owns and closes, or -1. */
    fun open(path: ByteArray): Int = try {
        tree.open(segments(path))?.detachFd() ?: -1
    } catch (e: Exception) {
        -1
    }

    companion object {
        const val MISSING = -1L
        const val DIRECTORY = -2L

        fun segments(path: ByteArray): List<String> =
            path.decodeToString().split('/').filter { it.isNotEmpty() && it != "." }
    }
}
