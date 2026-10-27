package be.discrip.core.disc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FileBlockDeviceTest {

    @TempDir
    lateinit var directory: File

    private fun image(bytes: Int): File {
        val file = File(directory, "disc.iso")
        file.writeBytes(ByteArray(bytes) { (it / BlockDevice.BLOCK_SIZE + it).toByte() })
        return file
    }

    @Test
    fun `reads whole blocks and ignores a trailing partial block`() {
        val file = image(3 * 2048 + 1000)

        FileBlockDevice.open(file).use { device ->
            assertEquals(3, device.blockCount)
            val buffer = ByteArray(2 * 2048 + 10)
            device.read(1, 2, buffer, offset = 10)
            assertContentEquals(file.readBytes().copyOfRange(2048, 3 * 2048), buffer.copyOfRange(10, buffer.size))
        }
    }

    @Test
    fun `reads outside the image fail`() {
        FileBlockDevice.open(image(2 * 2048)).use { device ->
            assertThrows<IOException> { device.read(2, 1, ByteArray(2048)) }
            assertThrows<IOException> { device.read(1, 2, ByteArray(4096)) }
            assertThrows<IOException> { device.read(-1, 1, ByteArray(2048)) }
        }
    }

    @Test
    fun `a buffer that is too small is refused`() {
        FileBlockDevice.open(image(2 * 2048)).use { device ->
            assertThrows<IllegalArgumentException> { device.read(0, 2, ByteArray(4095)) }
        }
    }
}
