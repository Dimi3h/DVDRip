package be.discrip.core.disc

import be.discrip.core.scsi.OpticalDrive
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** A disc as an array of 2048-byte logical blocks: a USB drive or an ISO image. */
interface BlockDevice : Closeable {
    val blockCount: Long

    /** Reads [blocks] blocks starting at [lba] into [buffer] at [offset]. */
    fun read(lba: Long, blocks: Int, buffer: ByteArray, offset: Int = 0)

    companion object {
        const val BLOCK_SIZE = 2048
    }
}

/** The disc in a USB drive. Closing it leaves the drive open; the drive's owner closes that. */
class OpticalDriveBlockDevice(
    private val drive: OpticalDrive,
    override val blockCount: Long,
) : BlockDevice {
    override fun read(lba: Long, blocks: Int, buffer: ByteArray, offset: Int) {
        requireInRange(lba, blocks, blockCount)
        drive.read(lba, blocks, buffer, offset)
    }

    override fun close() = Unit
}

/**
 * An ISO image read through a [FileChannel] with positional reads, so it works for files and for
 * the file descriptors Android's document picker hands out. A trailing partial block is ignored.
 */
class FileBlockDevice(
    private val channel: FileChannel,
    private val owner: Closeable? = null,
) : BlockDevice {

    override val blockCount: Long = channel.size() / BlockDevice.BLOCK_SIZE

    override fun read(lba: Long, blocks: Int, buffer: ByteArray, offset: Int) {
        requireInRange(lba, blocks, blockCount)
        val length = blocks * BlockDevice.BLOCK_SIZE
        require(offset >= 0 && offset + length <= buffer.size) { "Buffer too small" }
        val target = ByteBuffer.wrap(buffer, offset, length)
        var position = lba * BlockDevice.BLOCK_SIZE
        while (target.hasRemaining()) {
            val read = channel.read(target, position)
            if (read < 0) throw EOFException("Image ended at byte $position")
            position += read
        }
    }

    override fun close() {
        channel.close()
        owner?.close()
    }

    companion object {
        fun open(file: File): FileBlockDevice {
            val raf = RandomAccessFile(file, "r")
            return FileBlockDevice(raf.channel, raf)
        }
    }
}

private fun requireInRange(lba: Long, blocks: Int, blockCount: Long) {
    if (lba < 0 || blocks < 0 || lba + blocks > blockCount) {
        throw IOException("Blocks $lba..${lba + blocks - 1} are outside the disc (0..${blockCount - 1})")
    }
}
