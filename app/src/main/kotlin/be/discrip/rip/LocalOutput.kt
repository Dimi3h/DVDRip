package be.discrip.rip

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import be.discrip.core.naming.RipPath
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The MKV a rip writes into the local folder. It is created as `<name>.part` in the movie folder
 * and renamed when the rip is complete, so Plex or a file manager never sees half a film under
 * the final name. Storage that cannot rename gets the final name straight away.
 */
class LocalOutput private constructor(
    private val resolver: ContentResolver,
    private val folder: Uri,
    private val fileName: String,
    document: Uri,
    private val temporary: Boolean,
) : LocalRipOutput {

    /** The file; after [complete], under its final name. */
    var document: Uri = document
        private set

    override fun open(): EngineFile = EngineFile.Descriptor(
        resolver.openFileDescriptor(document, "rw") ?: throw FileNotFoundException("Cannot open $document"),
    )

    override fun read(): ParcelFileDescriptor =
        resolver.openFileDescriptor(document, "r") ?: throw FileNotFoundException("Cannot open $document")

    override fun complete() {
        if (!temporary) return
        findChild(resolver, folder, fileName)?.let { DocumentsContract.deleteDocument(resolver, it) }
        document = DocumentsContract.renameDocument(resolver, document, fileName)
            ?: throw IOException("Renaming to $fileName failed")
    }

    override fun discard() {
        try {
            DocumentsContract.deleteDocument(resolver, document)
        } catch (e: FileNotFoundException) {
            // Already gone.
        }
    }

    companion object {

        private const val MKV_MIME = "video/x-matroska"
        private const val PART_SUFFIX = ".part"

        /** The finished file at [path] below [treeUri], if an earlier rip made it. */
        fun existing(resolver: ContentResolver, treeUri: Uri, path: RipPath): Uri? {
            var folder = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            for (segment in path.folderSegments) {
                folder = findChild(resolver, folder, segment, directory = true) ?: return null
            }
            return findChild(resolver, folder, path.fileName)
        }

        /** Creates the folders of [path] as needed and the file to write. */
        fun create(resolver: ContentResolver, treeUri: Uri, path: RipPath): LocalOutput {
            var folder = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            for (segment in path.folderSegments) {
                folder = findChild(resolver, folder, segment, directory = true)
                    ?: DocumentsContract.createDocument(resolver, folder, Document.MIME_TYPE_DIR, segment)
                    ?: throw IOException("Cannot create the folder $segment")
            }
            val partName = path.fileName + PART_SUFFIX
            findChild(resolver, folder, partName)?.let { DocumentsContract.deleteDocument(resolver, it) }
            val part = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", partName)
                ?: throw IOException("Cannot create $partName")
            if (supportsRename(resolver, part)) return LocalOutput(resolver, folder, path.fileName, part, temporary = true)

            // No renaming here: write under the final name, replacing an earlier rip.
            DocumentsContract.deleteDocument(resolver, part)
            findChild(resolver, folder, path.fileName)?.let { DocumentsContract.deleteDocument(resolver, it) }
            val file = DocumentsContract.createDocument(resolver, folder, MKV_MIME, path.fileName)
                ?: throw IOException("Cannot create ${path.fileName}")
            return LocalOutput(resolver, folder, path.fileName, file, temporary = false)
        }

        private fun supportsRename(resolver: ContentResolver, document: Uri): Boolean =
            resolver.query(document, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) and Document.FLAG_SUPPORTS_RENAME != 0
            } ?: false

        private fun findChild(resolver: ContentResolver, folder: Uri, name: String, directory: Boolean = false): Uri? {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR
                    if (cursor.getString(1) == name && isDirectory == directory) {
                        return DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))
                    }
                }
            }
            return null
        }
    }
}
