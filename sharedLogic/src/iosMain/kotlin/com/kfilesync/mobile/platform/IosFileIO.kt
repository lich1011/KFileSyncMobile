package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.FileSink
import com.kfilesync.mobile.domain.port.FileSource
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.fileHandleForReadingFromURL
import platform.Foundation.fileHandleForWritingToURL

/**
 * iOS `FileSource` (T2.2).
 *
 * The [locator] is an NSURL absolute string. For files picked via
 * `UIDocumentPickerViewController`, the caller has already invoked
 * `startAccessingSecurityScopedResource` and persisted a bookmark; this
 * adapter opens an `NSFileHandle` once per locator and caches it.
 *
 * Reads happen on `Dispatchers.Default` - the new Kotlin/Native memory
 * model lets an `NSFileHandle` cross suspend points without ceremony.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileSource : FileSource {

    private val handles = mutableMapOf<String, NSFileHandle>()
    private val sizes = mutableMapOf<String, Long>()

    override suspend fun size(locator: String): Long = withContext(Dispatchers.Default) {
        sizes[locator]?.let { return@withContext it }
        // For `file://` URLs we can ask NSFileManager directly. For non-file
        // URLs (security-scoped bookmarks for shared sources), we fall back
        // to seeking the file handle to end and asking offsetInFile.
        val url = NSURL(string = locator)
        val path = url.path
        val computed: Long = if (path != null) {
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue ?: handleSize(locator)
        } else {
            handleSize(locator)
        }
        sizes[locator] = computed
        computed
    }
 
    private fun handleSize(locator: String): Long = memScoped {
        val h = openOrReuse(locator)
        val offsetVar = alloc<ULongVar>()
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val success = h.seekToEndReturningOffset(offsetVar.ptr, errorPtr.ptr)
        h.seekToOffset(0u, errorPtr.ptr)
        if (success) offsetVar.value.toLong() else 0L
    }
 
    override suspend fun readChunk(
        locator: String,
        chunkIndex: Int,
        chunkSize: Int,
        destination: ByteArray
    ): Int = withContext(Dispatchers.Default) {
        val handle = openOrReuse(locator)
        val offset = chunkIndex.toLong() * chunkSize.toLong()
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            handle.seekToOffset(offset.toULong(), errorPtr.ptr)
            val data: NSData = handle.readDataUpToLength(chunkSize.toULong(), errorPtr.ptr) ?: return@withContext 0
            val len = data.length.toInt()
            if (len > 0) {
                val bytes = data.toByteArray()
                bytes.copyInto(destination, 0, 0, len.coerceAtMost(destination.size))
            }
            len
        }
    }
 
    override suspend fun readWhole(locator: String): ByteArray = withContext(Dispatchers.Default) {
        val handle = openOrReuse(locator)
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            handle.seekToOffset(0u, errorPtr.ptr)
            handle.readDataToEndOfFileAndReturnError(errorPtr.ptr)?.toByteArray() ?: ByteArray(0)
        }
    }
 
    override suspend fun close(locator: String) = withContext(Dispatchers.Default) {
        handles.remove(locator)?.let { handle ->
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                runCatching { handle.closeAndReturnError(errorPtr.ptr) }
            }
        }
        Unit
    }

    private fun openOrReuse(locator: String): NSFileHandle {
        handles[locator]?.let { return it }
        val url = NSURL(string = locator)
        val handle = NSFileHandle.fileHandleForReadingFromURL(url, null)
            ?: error("cannot open $locator for reading")
        handles[locator] = handle
        return handle
    }
}

/**
 * iOS `FileSink` (T2.3).
 *
 * Temp files live under `NSTemporaryDirectory()/kfilesync_inbox/`. On
 * [finalize] we either move into the user-selected destination (security-
 * scoped bookmark; Phase 5 polish) or leave the file in temp and surface
 * its `file://` URL string so the UI can present it via the share sheet.
 *
 * Path handling uses plain Kotlin String concatenation rather than
 * NSString.stringByAppendingPathComponent - the K/N bridge for NSString
 * casting can be finicky across Foundation overloads, and the directory
 * is a regular POSIX path here.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileSink : FileSink {

    private val inboxDir: String by lazy {
        val temp = NSTemporaryDirectory()
        val dir = if (temp.endsWith("/")) "${temp}kfilesync_inbox" else "$temp/kfilesync_inbox"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        dir
    }

    private val openHandles = mutableMapOf<String, NSFileHandle>()

    override suspend fun openTemp(suggestedName: String): String = withContext(Dispatchers.Default) {
        val safe = suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val unique = "${kotlin.random.Random.nextLong().toString(16)}_$safe"
        val path = "$inboxDir/$unique"
        NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
        val url = NSURL.fileURLWithPath(path)
        val handle = NSFileHandle.fileHandleForWritingToURL(url, null)
            ?: error("cannot open $path for writing")
        val locator = url.absoluteString!!
        openHandles[locator] = handle
        locator
    }

    override suspend fun writeChunk(
        locator: String,
        offset: Long,
        data: ByteArray,
        length: Int
    ) = withContext(Dispatchers.Default) {
        val handle = openHandles[locator] ?: run {
            val url = NSURL(string = locator)
            val h = NSFileHandle.fileHandleForWritingToURL(url, null)
                ?: error("cannot re-open $locator for writing")
            openHandles[locator] = h
            h
        }
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            handle.seekToOffset(offset.toULong(), errorPtr.ptr)
            val bytes = if (length == data.size) data else data.copyOf(length)
            handle.writeData(bytes.toNSData(), errorPtr.ptr)
        }
        Unit
    }
 
    override suspend fun finalize(
        tempLocator: String,
        targetDirectory: String?,
        fileName: String
    ): String = withContext(Dispatchers.Default) {
        openHandles.remove(tempLocator)?.let { handle ->
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                runCatching { handle.closeAndReturnError(errorPtr.ptr) }
            }
        }
        val tempUrl = NSURL(string = tempLocator)
        if (targetDirectory.isNullOrBlank()) {
            // Fallback: leave in inbox dir. Phase 5 wires share-sheet/destination.
            return@withContext tempLocator
        }
        val destDir = if (targetDirectory.endsWith("/")) targetDirectory.dropLast(1) else targetDirectory
        val destPath = "$destDir/$fileName"
        val destUrl = NSURL.fileURLWithPath(destPath)
        val moved = NSFileManager.defaultManager.moveItemAtURL(tempUrl, destUrl, null)
        if (!moved) {
            Napier.w("iOS finalize move failed for $fileName; leaving in inbox")
            return@withContext tempLocator
        }
        destUrl.absoluteString!!
    }
 
    override suspend fun discard(locator: String) = withContext(Dispatchers.Default) {
        openHandles.remove(locator)?.let { handle ->
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                runCatching { handle.closeAndReturnError(errorPtr.ptr) }
            }
        }
        runCatching {
            NSFileManager.defaultManager.removeItemAtURL(NSURL(string = locator), null)
        }
        Unit
    }
}