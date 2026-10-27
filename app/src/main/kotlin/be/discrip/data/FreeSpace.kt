package be.discrip.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/**
 * Free space on the storage of a folder picked with ACTION_OPEN_DOCUMENT_TREE. Only local storage
 * (internal storage, SD cards, USB sticks) can tell; for other document providers the answer is
 * null and the rip checks the space on the file it creates instead.
 */
object FreeSpace {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun of(context: Context, treeUri: Uri): Long? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val volumeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()?.substringBefore(':')
            ?: return null
        val storage = context.getSystemService(StorageManager::class.java) ?: return null
        val volume = storage.storageVolumes.firstOrNull { volume ->
            if (volume.isPrimary) volumeId == "primary" else volume.uuid.equals(volumeId, ignoreCase = true)
        } ?: return null
        val directory = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volume.directory
            volume.isPrimary -> @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()
            else -> volume.uuid?.let { File("/storage/$it") }
        } ?: return null
        return runCatching { StatFs(directory.path).availableBytes }.getOrNull()
    }

    /** Free space in the app's own storage, where NAS rips wait for their upload. */
    fun ofAppStorage(context: Context): Long? = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()
}
