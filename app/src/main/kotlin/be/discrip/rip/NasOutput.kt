package be.discrip.rip

import android.system.OsConstants
import be.discrip.core.nas.NasException
import be.discrip.core.nas.NasFile
import be.discrip.core.nas.NasProblem
import be.discrip.core.nas.NasShare
import be.discrip.core.naming.RipPath

/**
 * A file on the NAS as the engine sees it. The engine only learns an errno; the first
 * [NasException] is kept in [error] so the app can say what went wrong.
 */
class NasNativeFile(private val file: NasFile) : NativeFile {

    @Volatile
    var error: NasException? = null
        private set

    override fun write(data: ByteArray, length: Int, position: Long): Int = try {
        file.write(data, 0, length, position)
        0
    } catch (e: NasException) {
        failed(e)
    }

    override fun read(buffer: ByteArray, length: Int, position: Long): Int = try {
        file.read(buffer, 0, length, position).coerceAtLeast(0)
    } catch (e: NasException) {
        failed(e)
    }

    override fun size(): Long = try {
        file.length
    } catch (e: NasException) {
        failed(e).toLong()
    }

    fun close() {
        try {
            file.close()
        } catch (e: NasException) {
            // Every byte was written and acknowledged already; a lost connection at the end is harmless.
        }
    }

    private fun failed(e: NasException): Int {
        if (error == null) error = e
        return -when (e.problem) {
            NasProblem.FULL -> OsConstants.ENOSPC
            NasProblem.ACCESS_DENIED -> OsConstants.EACCES
            else -> OsConstants.EIO
        }
    }
}

/**
 * The MKV written straight to the share by the engine ("write directly to the NAS"): as
 * `<name>.part` in the film's folder, renamed when the rip is complete, like [LocalOutput].
 */
class NasOutput(private val share: NasShare, private val path: RipPath) : RipOutput {

    private val partPath = path.relativeFile + NasShare.PART_SUFFIX
    private var file: NasNativeFile? = null

    /** The first NAS failure while the engine wrote or read, if any. */
    val error: NasException? get() = file?.error

    override fun open(): EngineFile {
        share.ensureFolders(path.folderSegments)
        val opened = NasNativeFile(share.create(partPath))
        file = opened
        return EngineFile.Callbacks(opened, opened::close)
    }

    override fun complete() = share.rename(partPath, path.relativeFile)

    override fun discard() {
        try {
            share.delete(partPath)
        } catch (e: NasException) {
            // The NAS is gone; the .part file stays behind and the next rip of this film replaces it.
        }
    }
}
