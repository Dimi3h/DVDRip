package be.discrip.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

/**
 * The folder the user picked with ACTION_OPEN_DOCUMENT_TREE. The read/write grant is persisted so
 * the app can keep writing there after restarts; it can be revoked from system settings or by
 * removing the SD card or USB stick, which [hasAccess] detects.
 */
class LocalFolderAccess(private val context: Context) {

    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Persists access to [uri] and returns a human-readable name for it. */
    fun takePermission(uri: Uri): String {
        context.contentResolver.takePersistableUriPermission(uri, flags)
        return displayName(uri)
    }

    fun releasePermission(uriString: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(uriString.toUri(), flags)
        } catch (e: SecurityException) {
            // Already gone; nothing to release.
        }
    }

    fun hasAccess(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val uri = uriString.toUri()
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return granted && DocumentFile.fromTreeUri(context, uri)?.canWrite() == true
    }

    /** "Internal shared storage › Movies" or "SanDisk USB › Films". */
    fun displayName(uri: Uri): String {
        val folder = DocumentFile.fromTreeUri(context, uri)?.name
        return listOfNotNull(volumeName(uri), folder).distinct().joinToString(" › ").ifEmpty { uri.toString() }
    }

    private fun volumeName(uri: Uri): String? {
        val volumeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()?.substringBefore(':')
            ?: return null
        val storage = context.getSystemService(StorageManager::class.java) ?: return null
        val volume = storage.storageVolumes.firstOrNull { volume ->
            if (volume.isPrimary) volumeId == "primary" else volume.uuid.equals(volumeId, ignoreCase = true)
        }
        return volume?.getDescription(context)
    }
}
