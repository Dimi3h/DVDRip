package be.discrip.rip

/**
 * A file the engine writes a rip into, or reads a finished one back from, through Kotlin rather
 * than a descriptor: the MKV on the NAS (`dr_file` in the engine). Native code calls it on the
 * thread of the rip or probe; failures come back as negative errno values (OsConstants), never as
 * exceptions.
 */
interface NativeFile {
    /** Writes the first [length] bytes of [data] at [position]: 0, or a negative errno. */
    fun write(data: ByteArray, length: Int, position: Long): Int

    /** Reads up to [length] bytes into [buffer] at [position]: the count, 0 at the end, or a negative errno. */
    fun read(buffer: ByteArray, length: Int, position: Long): Int

    /** The file's size, or a negative errno. */
    fun size(): Long
}
