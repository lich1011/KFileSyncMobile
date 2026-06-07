package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.DirectoryScanner
import com.kfilesync.mobile.domain.port.ScannedEntry
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSDirectoryEnumerationSkipsHiddenFiles
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970

/**
 * iOS `DirectoryScanner` (T4.1) using `NSFileManager.enumeratorAtURL`.
 *
 * The [rootLocator] is an NSURL absolute string \u2014 either a file:// URL
 * for the app's own sandbox or a security-scoped bookmark resolved at
 * accept-time (the Swift bridge calls `startAccessingSecurityScopedResource`
 * before this scanner runs).
 *
 * `NSDirectoryEnumerator` walks the tree depth-first; we re-sort by
 * relative path for determinism, matching the Android adapter.
 *
 * Attribute keys: we use the Foundation constants ([NSFileType],
 * [NSFileSize], [NSFileModificationDate]) rather than string literals
 * \u2014 the K/N bridge exposes the typed keys so a future Foundation
 * version that renames them surfaces as a compile error, not a silent
 * runtime miss.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDirectoryScanner : DirectoryScanner {

    override suspend fun scan(rootLocator: String): List<ScannedEntry> = withContext(Dispatchers.Default) {
        val rootUrl = NSURL(string = rootLocator)
        val rootPath = rootUrl.path ?: return@withContext emptyList()
        val out = mutableListOf<ScannedEntry>()

        val enumerator = NSFileManager.defaultManager.enumeratorAtURL(
            url = rootUrl,
            includingPropertiesForKeys = null,
            options = NSDirectoryEnumerationSkipsHiddenFiles,
            errorHandler = null
        ) ?: return@withContext emptyList()

        while (true) {
            val next = enumerator.nextObject() ?: break
            val childUrl = next as? NSURL ?: continue
            val childPath = childUrl.path ?: continue
            val relativePath = childPath.removePrefix(rootPath).removePrefix("/")
            if (relativePath.isEmpty()) continue

            val (size, mtimeMs, isDir) = readAttrs(childPath)
            out += ScannedEntry(
                locator = childUrl.absoluteString ?: continue,
                relativePath = relativePath,
                isDirectory = isDir,
                sizeBytes = size,
                lastModifiedEpochMs = mtimeMs
            )
        }

        out.sortedBy { it.relativePath }
    }

    override suspend fun resolve(rootLocator: String, relativePath: String): ScannedEntry? =
        withContext(Dispatchers.Default) {
            val rootUrl = NSURL(string = rootLocator)
            val rootPath = rootUrl.path ?: return@withContext null
            val joined = "$rootPath/$relativePath"
            if (!NSFileManager.defaultManager.fileExistsAtPath(joined)) return@withContext null
            val childUrl = NSURL.fileURLWithPath(joined)
            val (size, mtimeMs, isDir) = readAttrs(joined)
            ScannedEntry(
                locator = childUrl.absoluteString ?: return@withContext null,
                relativePath = relativePath,
                isDirectory = isDir,
                sizeBytes = size,
                lastModifiedEpochMs = mtimeMs
            )
        }

    /**
     * Returns (size, mtimeMs?, isDirectory) using NSFileManager attributes API.
     *
     * Three reads in one call so [scan] doesn't open the same attribute
     * dictionary three times per entry.
     */
    private fun readAttrs(path: String): Triple<Long, Long?, Boolean> {
        return runCatching {
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
                ?: return@runCatching Triple(0L, null, false)
            val size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
            val mtime = (attrs[NSFileModificationDate] as? NSDate)?.let {
                (it.timeIntervalSince1970 * 1000.0).toLong()
            }
            val isDir = (attrs[NSFileType] as? String) == NSFileTypeDirectory
            Triple(size, mtime, isDir)
        }.getOrElse {
            Napier.w("readAttrs($path) failed: ${it.message}")
            Triple(0L, null, false)
        }
    }
}