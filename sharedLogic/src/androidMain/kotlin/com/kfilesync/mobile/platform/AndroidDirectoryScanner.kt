package com.kfilesync.mobile.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.kfilesync.mobile.domain.port.DirectoryScanner
import com.kfilesync.mobile.domain.port.ScannedEntry
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android `DirectoryScanner` (T4.1) using the Storage Access Framework.
 *
 * The [rootLocator] is the SAF tree URI the user picked when accepting a
 * share invitation; we walk it via `DocumentsContract.buildChildDocumentsUriUsingTree`
 * and the children query. Two characteristics matter:
 *
 * 1. The same content URI is opaque to us - we can't construct a child
 * URI from a parent URI + filename string. Every child is obtained
 * by querying the children cursor and pulling its `DOCUMENT_ID`.
 * 2. SAF doesn't expose POSIX paths. We synthesise [relativePath] by
 * tracking the directory chain during traversal: the root has
 * `relativePath = ""`, its direct children get `"<name>"`, grandchildren
 * get `"<dir>/<name>"`, and so on. This is what [IgnoreSpec] and the
 * sync index key on.
 *
 * Traversal is breadth-first (ArrayDeque queue), deterministic-ordered (we
 * sort each directory's children by display name before enqueuing). The
 * implementation is non-recursive so deep trees don't blow the JVM stack.
 */
class AndroidDirectoryScanner(private val context: Context) : DirectoryScanner {

    override suspend fun scan(rootLocator: String): List<ScannedEntry> = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(rootLocator)
        val treeDocId = DocumentsContract.getTreeDocumentId(rootUri)
        val rootChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, treeDocId)
        val out = mutableListOf<ScannedEntry>()
        val queue = ArrayDeque<Pair<Uri, String>>()
        queue.addLast(rootChildrenUri to "")

        while (queue.isNotEmpty()) {
            val (childrenUri, prefix) = queue.removeFirst()
            val rows = queryChildren(rootUri, childrenUri, prefix)
            // Sort for determinism.
            rows.sortedBy { it.relativePath }.forEach { entry ->
                out += entry
                if (entry.isDirectory) {
                    val childDocUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, docIdOf(entry.locator))
                    queue.addLast(childDocUri to entry.relativePath)
                }
            }
        }
        out
    }

    override suspend fun resolve(rootLocator: String, relativePath: String): ScannedEntry? =
        withContext(Dispatchers.IO) {
            // SAF can't seek by path - we walk until we find the target.
            // Phase 4 doesn't optimise this; sync sessions hold the scan
            // result anyway, so the lookup is rare. If profiling shows it's
            // hot, we'll add a per-share path -> docId cache.
            scan(rootLocator).firstOrNull { it.relativePath == relativePath }
        }

    /**
     * Query one children cursor and translate every row into a [ScannedEntry].
     * [rootUri] is the user-picked tree URI - we need it (not [childrenUri])
     * to build per-row document URIs because
     * `buildDocumentUriUsingTree` expects the tree URI as its first argument.
     * `prefix` is the path of the parent (empty for root children).
     */
    private fun queryChildren(rootUri: Uri, childrenUri: Uri, prefix: String): List<ScannedEntry> {
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = mutableListOf<ScannedEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, cols, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val mtime = if (cursor.isNull(4)) null else cursor.getLong(4)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                    out += ScannedEntry(
                        locator = docUri.toString(),
                        relativePath = relPath,
                        isDirectory = isDir,
                        sizeBytes = size,
                        lastModifiedEpochMs = mtime
                    )
                }
            }
        }.onFailure { Napier.w("queryChildren($childrenUri) failed: ${it.message}") }
        return out
    }

    /** Pull the documentId out of a content URI built via `buildDocumentUriUsingTree`. */
    private fun docIdOf(uriString: String): String {
        val uri = Uri.parse(uriString)
        return DocumentsContract.getDocumentId(uri)
    }
}