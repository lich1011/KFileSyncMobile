package com.kfilesync.mobile.platform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        val tempFile: File? = null,
        var positionChunk: Int
    )

    private val streams = java.util.concurrent.ConcurrentHashMap<String, OpenStream>()

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
        val raf = open.raf ?: run {
            val (r, tmp) = openRandomAccess(locator)
            streams[locator] = open.copy(raf = r, tempFile = tmp, input = null)
            r
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
            runCatching { s.tempFile?.delete() }
        }
        Unit
    }

    private fun openOrReuse(locator: String, chunkIndex: Int): OpenStream {
        val existing = streams[locator]
        if (existing != null) return existing
        val stream = context.contentResolver.openInputStream(Uri.parse(locator))
            ?: throw IllegalStateException("cannot open URI $locator")
        val fresh = OpenStream(input = stream, raf = null, tempFile = null, positionChunk = chunkIndex)
        streams[locator] = fresh
        return fresh
    }

    private fun openRandomAccess(locator: String): Pair<RandomAccessFile, File> {
        val temp = File.createTempFile("kfilesync_src_", ".bin", context.cacheDir)
        context.contentResolver.openInputStream(Uri.parse(locator))?.use { input ->
            FileOutputStream(temp).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("cannot materialise URI $locator")
        return Pair(RandomAccessFile(temp, "r"), temp)
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
            // Default destination: device's public Downloads/KFileSync folder so
            // received files are immediately visible in the system Files/Downloads app.
            return@withContext saveToPublicDownloads(tempFile, fileName)
        }
        // User-selected custom directory (SAF tree URI): copy via DocumentFile + ContentResolver.
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

    private fun saveToPublicDownloads(tempFile: File, fileName: String): String {
        val safeName = fileName.substringAfterLast('/').ifBlank { "received_file" }
        // Android 10+ (API 29+): MediaStore.Downloads requires no storage permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val savedUri = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, guessMime(safeName))
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/KFileSync"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore.Downloads insert returned null")
                try {
                    resolver.openOutputStream(uri, "w")?.use { out ->
                        FileInputStream(tempFile).use { input -> input.copyTo(out) }
                    } ?: error("MediaStore openOutputStream failed")
                    val ready = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, ready, null, null)
                    tempFile.delete()
                    uri.toString()
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
            }.onFailure {
                Napier.w("MediaStore Downloads save failed, falling back to filesystem: ${it.message}")
            }.getOrNull()
            if (savedUri != null) return savedUri
        }

        // API 26-28 or fallback: public Downloads/KFileSync -> app external Downloads -> cache inbox.
        val candidateDirs = listOfNotNull(
            @Suppress("DEPRECATION")
            runCatching {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "KFileSync"
                )
            }.getOrNull(),
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "KFileSync") },
            inboxDir
        )
        for (dir in candidateDirs) {
            val saved = runCatching {
                if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed for ${dir.absolutePath}")
                val dest = File(dir, ensureUniqueName(dir, safeName))
                if (!tempFile.renameTo(dest)) {
                    FileInputStream(tempFile).use { input ->
                        FileOutputStream(dest).use { out -> input.copyTo(out) }
                    }
                    tempFile.delete()
                }
                dest.absolutePath
            }.getOrNull()
            if (saved != null) return saved
        }
        return tempFile.absolutePath
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