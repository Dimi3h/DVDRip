package be.discrip.rip

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** The file the engine writes: a descriptor, or a [NativeFile] such as one on the NAS. */
sealed interface EngineFile : Closeable {

    class Descriptor(val descriptor: ParcelFileDescriptor) : EngineFile {
        override fun close() = descriptor.close()
    }

    class Callbacks(val file: NativeFile, private val onClose: () -> Unit) : EngineFile {
        override fun close() = onClose()
    }
}

/** Where a rip's file is written. */
interface RipOutput {
    /** Opens the file for the engine, which seeks back while writing and reads the result back. */
    fun open(): EngineFile

    /** Gives the finished file its final name, replacing an earlier rip of the same film. */
    fun complete()

    /** Removes the unfinished file. */
    fun discard()
}

/** An output on this device, which can be read again to upload it to the NAS. */
interface LocalRipOutput : RipOutput {
    /** The finished file, for reading; after [complete]. */
    fun read(): ParcelFileDescriptor
}

/** Makes the output of a job in the local folder; the app writes there with [LocalOutput]. */
fun interface RipOutputs {
    fun create(job: RipJob, target: LocalTarget): LocalRipOutput
}

/**
 * The MKV of a NAS rip in the app's own storage, until it has been uploaded. Not named after the
 * film: [file] is unique to the rip, so a failed upload can wait there for another try.
 */
class StagedOutput(val file: File) : LocalRipOutput {

    override fun open(): EngineFile = EngineFile.Descriptor(
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE,
        ),
    )

    override fun complete() = Unit

    override fun discard() {
        file.delete()
    }

    override fun read(): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
}
