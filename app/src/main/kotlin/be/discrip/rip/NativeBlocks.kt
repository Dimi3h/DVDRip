package be.discrip.rip

/**
 * A disc the engine reads through Kotlin rather than a descriptor: the disc in a USB drive
 * (`dr_block_source` in the engine). Native code calls it on the thread of the engine call;
 * failures come back as -1, never as exceptions.
 */
interface NativeBlocks {
    /**
     * Reads up to [count] 2048-byte blocks at [lba] into [buffer]: the number read, fewer only at
     * the end of the disc, or -1.
     */
    fun read(lba: Long, count: Int, buffer: ByteArray): Int
}
