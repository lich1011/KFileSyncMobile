package com.kfilesync.mobile.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.kfilesync.mobile.domain.port.FileSink
import com.kfilesync.mobile.domain.port.FileSource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Android `FileSource` (T2.2).
 *
 * The [locator] is a `content://` URI string (the result of SAF
 * `ACTION_OPEN_DOCUMENT`). We open the stream once per file and cache it
 * indexed by locator so sequential `readChunk(..., ...)` calls don't re-open
 * the URI from scratch each time. Reads can be slow on cold-open if the
 * provider is a remote one (Google Drive), so the up-front "plan the
 * file" pass also computes size, sha256 and per-chunk hashes in a single
 * sweep.
 */
class AndroidFileSource(private val context: Context) : FileSource {

    /**
     * Per-locator scratch state. We keep a `RandomAccessFile` mirror when we
     * need to seek (resume after restart); otherwise we keep a streaming
     * `InputStream` for the linear plan-and-send walk.
     */
    private data class OpenStream(
        val input: InputStream?,
        val raf: RandomAccessFile?,
        var positionChunk: Int
    )

    private val streams = mutableMapOf<String, OpenStream>()

    override suspend fun size(locator: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(locator)
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrElse {
            Napier.w("AndroidFileSource.size($locator) failed: ${it.message}")
            0L
        }
    }

    override suspend fun readChunk(
        locator: String,
        chunkIndex: Int,
        chunkSize: Int,
        destination: ByteArray
    ): Int = withContext(Dispatchers.IO) {
        val open = openOrReuse(locator, chunkIndex)
        // Sequential fast path: stream + linear positionChunk.
        if (open.input != null && chunkIndex == open.positionChunk) {
            val read = readFully(open.input, destination, chunkSize)
            open.positionChunk += 1
            return@withContext read
        }
        // Random-access path (resume): open a RAF, seek by chunkSize.
        val raf = open.raf ?: openRandomAccess(locator).also {
            streams[locator] = open.copy(raf = it, input = null)
        }
        raf.seek(chunkIndex.toLong() * chunkSize.toLong())
        var total = 0
        while (total < chunkSize) {
            val n = raf.read(destination, total, chunkSize - total)
            if (n <= 0) break
            total += n
        }
        total
    }

    override suspend fun readWhole(locator: String): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(locator))?.use { it.readBytes() }
            ?: ByteArray(0)
    }

    override suspend fun close(locator: String) = withContext(Dispatchers.IO) {
        streams.remove(locator)?.let { s ->
            runCatching { s.input?.close() }
            runCatching { s.raf?.close() }
        }
        Unit
    }

    private fun openOrReuse(locator: String, chunkIndex: Int): OpenStream {
        val existing = streams[locator]
        if (existing != null) return existing
        val stream = context.contentResolver.openInputStream(Uri.parse(locator))
            ?: throw IllegalStateException("cannot open URI $locator")
        val fresh = OpenStream(input = stream, raf = null, positionChunk = chunkIndex)
        streams[locator] = fresh
        return fresh
    }

    /**
     * Open a RandomAccessFile for content URIs by copying into a temp file
     * once. AndroidContentProvider doesn't give us a seekable handle in
     * general; the materialised copy lives in cacheDir for the rest of the
     * transfer's lifetime, and the temp file is cleaned up on `close()`.
     */
    private fun openRandomAccess(locator: String): RandomAccessFile {
        val temp = File.createTempFile("kfilesync_src_", ".bin", context.cacheDir)
        context.contentResolver.openInputStream(Uri.parse(locator))?.use { input ->
            FileOutputStream(temp).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("cannot materialise URI $locator")
        return RandomAccessFile(temp, "r")
    }

    private fun readFully(input: InputStream, dest: ByteArray, target: Int): Int {
        var total = 0
        while (total < target) {
            val n = input.read(dest, total, target - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}

/**
 * Android `FileSink` (T2.3).
 *
 * Temp files live in `context.cacheDir/kfilesync_inbox`. On [finalize] we
 * either:
 * - copy into the user-selected SAF tree URI if `targetDirectory` is set
 * (this is what the UI does after the accept-confirm dialog), or
 * - leave the file in the cache dir and surface a `file:///cacheDir/_`
 * locator if no target was specified (Phase 2 fallback; Phase 5 wires
 * this to a default Downloads folder via MediaStore).
 */
class AndroidFileSink(private val context: Context) : FileSink {

    private val inboxDir: File by lazy {
        File(context.cacheDir, "kfilesync_inbox").apply { mkdirs() }
    }

    override suspend fun openTemp(suggestedName: String): String = withContext(Dispatchers.IO) {
        val safe = suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val file = File.createTempFile("inflight_", "_$safe", inboxDir)
        // RandomAccessFile mode "rw" creates the file if absent - we keep
        // an opened handle in [openFiles] so writeChunk doesn't pay the
        // open/close cost per chunk.
        openFiles[file.absolutePath] = RandomAccessFile(file, "rw")
        file.absolutePath
    }

    override suspend fun writeChunk(
        locator: String,
        offset: Long,
        data: ByteArray,
        length: Int
    ) = withContext(Dispatchers.IO) {
        val raf = openFiles[locator] ?: RandomAccessFile(locator, "rw").also { openFiles[locator] = it }
        raf.seek(offset)
        raf.write(data, 0, length)
    }

    override suspend fun finalize(
        tempLocator: String,
        targetDirectory: String?,
        fileName: String
    ): String = withContext(Dispatchers.IO) {
        runCatching { openFiles.remove(tempLocator)?.close() }
        val tempFile = File(tempLocator)
        if (targetDirectory.isNullOrBlank()) {
            // Fallback: leave in inbox dir. Phase 5 wires Downloads via MediaStore.
            val finalFile = File(inboxDir, ensureUniqueName(inboxDir, fileName))
            tempFile.renameTo(finalFile)
            return@withContext finalFile.absolutePath
        }
        // Tree URI path: copy via DocumentFile + ContentResolver.
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(targetDirectory))
            ?: error("target directory is not a SAF tree URI: $targetDirectory")
        val mime = guessMime(fileName)
        val finalDoc = tree.createFile(mime, fileName)
            ?: error("SAF createFile failed for $fileName")

        context.contentResolver.openOutputStream(finalDoc.uri, "w")?.use { out ->
            FileInputStream(tempFile).use { input -> input.copyTo(out) }
        } ?: error("SAF openOutputStream failed")

        tempFile.delete()
        finalDoc.uri.toString()
    }

    override suspend fun discard(locator: String) = withContext(Dispatchers.IO) {
        runCatching { openFiles.remove(locator)?.close() }
        runCatching { File(locator).delete() }
        Unit
    }

    private val openFiles = mutableMapOf<String, RandomAccessFile>()

    private fun ensureUniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (File(dir, "$base ($i)$ext").exists()) i += 1
        return "$base ($i)$ext"
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}