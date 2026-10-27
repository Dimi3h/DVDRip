package be.discrip.core.nas

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Against a real Samba server: `scripts/samba-test-server.sh start <dir>` starts one and prints the
 * DISCRIP_SMB_* environment these tests read; `./gradlew -p core smbTest` runs them.
 */
@Tag("smb")
class NasTest {

    private fun env(name: String) = System.getenv(name) ?: error("$name is not set; start scripts/samba-test-server.sh first")

    private val target = NasTarget(
        host = env("DISCRIP_SMB_HOST"),
        port = env("DISCRIP_SMB_PORT").toInt(),
        share = env("DISCRIP_SMB_SHARE"),
        username = env("DISCRIP_SMB_USER"),
        password = env("DISCRIP_SMB_PASSWORD"),
    )

    /** The share's folder on this machine, to check what arrived. */
    private val root = File(env("DISCRIP_SMB_ROOT"))
    private lateinit var share: NasShare

    @BeforeEach
    fun connect() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        share = Nas.connect(target)
    }

    @AfterEach
    fun disconnect() {
        share.close()
    }

    @Test
    fun `reports free space`() {
        assertTrue(share.freeBytes() > 0)
    }

    @Test
    fun `creates the Plex folders and uploads under a temporary name`() {
        val data = Random(1).nextBytes(9 * 1024 * 1024 + 123)
        val folders = listOf("Films", "Testfilm (2024) {tmdb-603}")
        val path = (folders + "Testfilm (2024) {tmdb-603}.mkv").joinToString("/")
        val progress = mutableListOf<Long>()

        share.ensureFolders(folders)
        share.upload(path, data.size.toLong(), { buffer, position -> copy(data, buffer, position) }, progress::add)

        assertContentEquals(data, File(root, path).readBytes())
        assertFalse(File(root, "$path.part").exists())
        assertEquals(data.size.toLong(), progress.last())
        assertEquals(data.size.toLong(), share.size(path))
        assertTrue(share.exists(path))
    }

    @Test
    fun `an upload replaces the file of an earlier rip`() {
        share.upload("film.mkv", 3, { buffer, position -> copy(byteArrayOf(1, 2, 3), buffer, position) })
        share.upload("film.mkv", 2, { buffer, position -> copy(byteArrayOf(9, 8), buffer, position) })

        assertContentEquals(byteArrayOf(9, 8), File(root, "film.mkv").readBytes())
    }

    @Test
    fun `writes anywhere in a file and reads it back, as the muxer does`() {
        share.create("direct.mkv.part").use { file ->
            file.write(ByteArray(16), 0, 16, 0)
            file.write(byteArrayOf(5, 6, 7), 0, 3, 16)
            // Back to the start to fill in the header, as Matroska does at the end.
            file.write(byteArrayOf(1, 2, 3, 4), 0, 4, 0)
            assertEquals(19, file.length)

            val back = ByteArray(32)
            assertEquals(19, file.read(back, 0, 32, 0))
            assertContentEquals(byteArrayOf(1, 2, 3, 4) + ByteArray(12) + byteArrayOf(5, 6, 7), back.copyOf(19))
            assertEquals(-1, file.read(back, 0, 32, 19))
            file.rename("direct.mkv")
        }
        assertTrue(File(root, "direct.mkv").exists())
        assertFalse(File(root, "direct.mkv.part").exists())
    }

    @Test
    fun `deletes and tells missing files apart`() {
        share.upload("gone.mkv", 1, { buffer, position -> copy(byteArrayOf(1), buffer, position) })
        share.delete("gone.mkv")

        assertFalse(share.exists("gone.mkv"))
        assertNull(share.size("gone.mkv"))
        share.delete("gone.mkv")
    }

    @Test
    fun `a wrong password is refused`() {
        assertThrows<NasException.LoginRefused> { Nas.connect(target.copy(password = "wrong")).close() }
    }

    @Test
    fun `an unknown share is named as such`() {
        assertThrows<NasException.ShareNotFound> { Nas.connect(target.copy(share = "nothere")).close() }
    }

    @Test
    fun `a server that does not answer is unreachable`() {
        assertThrows<NasException.Unreachable> { Nas.connect(target.copy(port = 1), connectTimeoutMillis = 3_000).close() }
    }

    @Test
    fun `a share the user may only read refuses folders and files`() {
        Nas.connect(target.copy(share = env("DISCRIP_SMB_READONLY_SHARE"))).use { readOnly ->
            assertTrue(readOnly.freeBytes() > 0)
            assertThrows<NasException.AccessDenied> { readOnly.ensureFolders(listOf("Films")) }
            assertThrows<NasException.AccessDenied> {
                readOnly.upload("film.mkv", 1, { buffer, position -> copy(byteArrayOf(1), buffer, position) })
            }
        }
    }

    @Test
    fun `closing twice is harmless`() {
        share.close()
        share.close()
    }

    private fun copy(source: ByteArray, buffer: ByteArray, position: Long): Int {
        val count = minOf(buffer.size.toLong(), source.size - position).toInt()
        source.copyInto(buffer, 0, position.toInt(), position.toInt() + count)
        return count
    }
}
