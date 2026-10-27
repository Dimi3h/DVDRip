package be.discrip.rip

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * A VIDEO_TS folder (or the folder above it) picked with ACTION_OPEN_DOCUMENT_TREE. Directory
 * listings are cached, since libdvdread looks up every file by listing its directory.
 */
class SafDvdTree(private val resolver: ContentResolver, private val treeUri: Uri) : DvdTree {

    private data class Node(val name: String, val documentId: String, val isDirectory: Boolean, val size: Long?)

    private val root = Node("", DocumentsContract.getTreeDocumentId(treeUri), isDirectory = true, size = null)
    private val listings = HashMap<String, List<Node>>()

    @Synchronized
    override fun stat(path: List<String>): DvdTree.Entry? {
        val node = resolve(path) ?: return null
        if (node.isDirectory) return DvdTree.Entry.Directory
        val size = node.size ?: openNode(node)?.use { it.statSize }?.takeIf { it >= 0 } ?: return null
        return DvdTree.Entry.File(size)
    }

    @Synchronized
    override fun list(path: List<String>): List<String>? {
        val node = resolve(path)?.takeIf { it.isDirectory } ?: return null
        return children(node)?.map { it.name }
    }

    @Synchronized
    override fun open(path: List<String>): ParcelFileDescriptor? {
        val node = resolve(path)?.takeIf { !it.isDirectory } ?: return null
        return openNode(node)
    }

    private fun openNode(node: Node): ParcelFileDescriptor? =
        resolver.openFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId), "r")

    /** Exact names first; FAT and exFAT sticks do not keep the case DVD tools wrote. */
    private fun resolve(path: List<String>): Node? {
        var node = root
        for (name in path) {
            if (!node.isDirectory) return null
            val children = children(node) ?: return null
            node = children.firstOrNull { it.name == name }
                ?: children.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return null
        }
        return node
    }

    private fun children(directory: Node): List<Node>? {
        listings[directory.documentId]?.let { return it }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.documentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val nodes = resolver.query(uri, columns, null, null, null)?.use { cursor ->
            val found = mutableListOf<Node>()
            while (cursor.moveToNext()) {
                val id: String = cursor.getString(0) ?: continue
                val name: String = cursor.getString(1) ?: continue
                val size: Long? = if (cursor.isNull(3)) null else cursor.getLong(3)
                found += Node(name, id, isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR, size = size)
            }
            found
        } ?: return null
        listings[directory.documentId] = nodes
        return nodes
    }
}
