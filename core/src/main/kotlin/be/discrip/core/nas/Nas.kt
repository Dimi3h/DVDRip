package be.discrip.core.nas

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** The SMB share rips go to, and the login for it. */
data class NasTarget(
    val host: String,
    val share: String,
    val username: String,
    val password: String,
    val domain: String = "",
    val port: Int = DEFAULT_PORT,
) {
    companion object {
        const val DEFAULT_PORT = 445
    }
}

/** Why the NAS could not be used. */
sealed class NasException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Unreachable(cause: Throwable) : NasException("The NAS cannot be reached: ${cause.message}", cause)
    class LoginRefused(cause: Throwable) : NasException("The NAS refused the user name or password", cause)
    class ShareNotFound(share: String, cause: Throwable) : NasException("The NAS has no share called $share", cause)
    class AccessDenied(path: String, cause: Throwable) : NasException("No permission for '$path' on the NAS", cause)
    class Full(cause: Throwable) : NasException("The share on the NAS is full", cause)
    class Failed(cause: Throwable) : NasException("The NAS reported an error: ${cause.message}", cause)
}

/** Connects to SMB 2 and 3 servers such as a Synology NAS, through smbj. */
object Nas {

    /**
     * Opens [target]'s share; throws [NasException]. Close the result when done. A NAS that does not
     * answer within [connectTimeoutMillis] is unreachable; one that stops answering fails the call
     * waiting for it after a minute, enough for disks that spin up first.
     */
    fun connect(target: NasTarget, connectTimeoutMillis: Int = 5_000): NasShare {
        val config = SmbConfig.builder()
            // BouncyCastle's MD4 for NTLM: neither Android nor the JDK offer it through JCE.
            .withSecurityProvider(BCSecurityProvider())
            .withSocketFactory(ProxySocketFactory(connectTimeoutMillis))
            // No socket read timeout: smbj's reader thread takes one for a dead connection, and the
            // connection is idle while the drive reads.
            .withTimeout(60, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        var connection: Connection? = null
        try {
            connection = client.connect(target.host, target.port)
            val session = try {
                connection.authenticate(AuthenticationContext(target.username, target.password.toCharArray(), target.domain.ifBlank { null }))
            } catch (e: SMBApiException) {
                throw NasException.LoginRefused(e)
            }
            val share = try {
                session.connectShare(target.share) as? DiskShare
                    ?: throw NasException.ShareNotFound(target.share, IOException("${target.share} is not a disk share"))
            } catch (e: SMBApiException) {
                session.close()
                throw if (e.status == NtStatus.STATUS_BAD_NETWORK_NAME) NasException.ShareNotFound(target.share, e) else translate(target.share, e)
            }
            return NasShare(client, connection, session, share)
        } catch (e: NasException) {
            connection?.let { quietly { it.close(true) } }
            client.close()
            throw e
        } catch (e: IOException) {
            connection?.let { quietly { it.close(true) } }
            client.close()
            throw NasException.Unreachable(e)
        } catch (e: SMBRuntimeException) {
            connection?.let { quietly { it.close(true) } }
            client.close()
            throw translate(target.share, e)
        }
    }

    internal fun translate(path: String, e: Throwable): NasException = when {
        e is NasException -> e
        e is SMBApiException -> when (e.status) {
            NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED -> NasException.LoginRefused(e)
            NtStatus.STATUS_BAD_NETWORK_NAME -> NasException.ShareNotFound(path, e)
            NtStatus.STATUS_ACCESS_DENIED -> NasException.AccessDenied(path, e)
            NtStatus.STATUS_DISK_FULL -> NasException.Full(e)
            NtStatus.STATUS_NETWORK_NAME_DELETED -> NasException.Unreachable(e)
            else -> NasException.Failed(e)
        }
        e is IOException || e.cause is IOException -> NasException.Unreachable(e)
        else -> NasException.Failed(e)
    }
}

/** Runs a close that may fail because the server dropped the connection first. */
private inline fun quietly(block: () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        // Gone already.
    } catch (e: SMBRuntimeException) {
        // Gone already.
    }
}

/** Runs an smbj call, turning its errors into [NasException]. */
private inline fun <T> nas(path: String, block: () -> T): T = try {
    block()
} catch (e: SMBRuntimeException) {
    throw Nas.translate(path, e)
} catch (e: NasException) {
    throw e
} catch (e: IOException) {
    throw Nas.translate(path, e)
}

/** smbj wants backslashes; DiscRip's paths use "/" like Plex and the settings do. */
private fun smb(path: String): String = path.trim('/').replace('/', '\\')

/**
 * One share on the NAS. Paths are relative to the share, with "/" between folders. Not safe for
 * use from several threads at once.
 */
class NasShare internal constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : Closeable {

    /** Free space for this user on the share. */
    fun freeBytes(): Long = nas("") { share.shareInformation.callerFreeSpace }

    fun exists(path: String): Boolean = nas(path) { share.fileExists(smb(path)) }

    /** The size of the file at [path], or null when there is none. */
    fun size(path: String): Long? = nas(path) {
        if (share.fileExists(smb(path))) share.getFileInformation(smb(path)).standardInformation.endOfFile else null
    }

    /** Creates the [folders] below the share's root where they are missing, top down. */
    fun ensureFolders(folders: List<String>) {
        var path = ""
        for (folder in folders) {
            path = if (path.isEmpty()) folder else "$path/$folder"
            nas(path) { if (!share.folderExists(smb(path))) share.mkdir(smb(path)) }
        }
    }

    fun delete(path: String) {
        nas(path) { if (share.fileExists(smb(path))) share.rm(smb(path)) }
    }

    /** Creates the file at [path] (emptying an existing one), for writing anywhere and reading back. */
    fun create(path: String): NasFile = nas(path) {
        NasFile(
            path,
            share.openFile(
                smb(path),
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE, AccessMask.DELETE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            ),
        )
    }

    fun openForReading(path: String): NasFile = nas(path) {
        NasFile(
            path,
            share.openFile(
                smb(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            ),
        )
    }

    /**
     * Copies [size] bytes from [read] (which fills a buffer from a position, returning the bytes
     * read) to [path]: written as `<path>.part`, its size checked, then renamed over any file of
     * that name. [onProgress] gets the bytes written so far.
     */
    fun upload(path: String, size: Long, read: (ByteArray, Long) -> Int, onProgress: (Long) -> Unit = {}) {
        val partPath = "$path$PART_SUFFIX"
        val buffer = ByteArray(UPLOAD_CHUNK)
        create(partPath).use { file ->
            var position = 0L
            while (position < size) {
                val got = read(buffer, position)
                if (got <= 0) throw IOException("The source ended at $position of $size bytes")
                file.write(buffer, 0, got, position)
                position += got
                onProgress(position)
            }
            file.flush()
            val written = file.length
            if (written != size) throw NasException.Failed(IOException("$written of $size bytes arrived on the NAS"))
            file.rename(path)
        }
    }

    override fun close() {
        quietly { share.close() }
        quietly { session.close() }
        quietly { connection.close() }
        client.close()
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val UPLOAD_CHUNK = 4 * 1024 * 1024
    }
}

/** An open file on the share. */
class NasFile internal constructor(private val path: String, private val file: File) : Closeable {

    val length: Long get() = nas(path) { file.length }

    fun write(data: ByteArray, offset: Int, length: Int, position: Long) {
        nas(path) {
            var done = 0L
            while (done < length) {
                val written = file.write(data, position + done, offset + done.toInt(), length - done.toInt())
                if (written <= 0) throw IOException("The NAS accepted no data at ${position + done}")
                done += written
            }
        }
    }

    /** Reads up to [length] bytes at [position]; the count read, or -1 at the end of the file. */
    fun read(buffer: ByteArray, offset: Int, length: Int, position: Long): Int = nas(path) { file.read(buffer, position, offset, length) }

    fun flush() {
        nas(path) { file.flush() }
    }

    /** Renames the file within its share, replacing a file at [to]. */
    fun rename(to: String) {
        nas(to) { file.rename(smb(to), true) }
    }

    override fun close() {
        nas(path) { file.close() }
    }
}
