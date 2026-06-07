package com.kfilesync.mobile.platform

import com.kfilesync.mobile.application.service.CacheEntry
import com.kfilesync.mobile.application.service.StorageMaintenanceAdapter
import com.kfilesync.mobile.db.KFileSyncDatabase
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSFileCreationDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.timeIntervalSince1970

/**
 * iOS storage-maintenance adapter (T5.5).
 *
 * Operates on the app's 'NSTemporaryDirectory()' + the
 * 'kfilesync_inbox/' subdir created by 'IosFileSink'. Apple's sandbox
 * isolates this directory per-app; the OS may also clean it up on its
 * own during low-storage events.
 *
 * VACUUM: SQLDelight 2.x doesn't surface the raw driver, so we rely on
 * SQLite's incremental auto-vacuum (set in DriverFactory.ios.kt) plus
 * periodic application-level cleanup. This is acceptable for the few-MB
 * sized DB this app maintains.
 */
@OptIn(ExperimentalForeignApi::class)
class IosStorageMaintenanceAdapter(
    private val database: KFileSyncDatabase
) : StorageMaintenanceAdapter {

    private val fm = NSFileManager.defaultManager

    override suspend fun listCacheFiles(): List<CacheEntry> = withContext(Dispatchers.Default) {
        val tmp = NSTemporaryDirectory()
        val inboxRoot = (tmp as NSString).stringByAppendingPathComponent(INBOX_DIR_NAME)
        val out = mutableListOf<CacheEntry>()
        appendDirectoryEntries(inboxRoot, out)
        // Also scan NSTemporaryDirectory's root for orphans from legacy paths.
        appendDirectoryEntries(tmp, out)
        out.distinctBy { it.locator }
    }

    override suspend fun deleteCacheFile(locator: String): Boolean = withContext(Dispatchers.Default) {
        val tmpRoot = NSTemporaryDirectory()
        if (!locator.startsWith(tmpRoot) && !locator.startsWith("file://$tmpRoot")) {
            Napier.w("StorageMaintenance(iOS) refused delete outside tmp: $locator")
            return@withContext false
        }
        val path = if (locator.startsWith("file://")) locator.removePrefix("file://") else locator
        // NSFileManager.removeItemAtPath:error: is a single boolean call;
        // we pass 'null' for the error pointer because we just log on failure.
        runCatching { fm.removeItemAtPath(path, null) }
            .onFailure { Napier.w("removeItemAtPath threw for $path: ${it.message}") }
            .getOrDefault(false)
    }

    override suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.Default) {
        listCacheFiles().sumOf { it.sizeBytes }
    }

    override suspend fun trimCacheTo(maxBytes: Long): Long = withContext(Dispatchers.Default) {
        val all = listCacheFiles().sortedBy { it.lastModifiedEpochMs }
        var current = all.sumOf { it.sizeBytes }
        if (current <= maxBytes) return@withContext 0L
        var reclaimed = 0L
        for (entry in all) {
            if (current <= maxBytes) break
            val ok = deleteCacheFile(entry.locator)
            if (ok) {
                current -= entry.sizeBytes
                reclaimed += entry.sizeBytes
            }
        }
        reclaimed
    }

    override suspend fun vacuumDatabase(): Boolean = withContext(Dispatchers.Default) {
        // SQLDelight 2.x doesn't surface the SqlDriver from the generated
        // Database class. We rely on SQLite's PRAGMA auto_vacuum = INCREMENTAL
        // set at DB creation. This method is a soft signal that maintenance
        // is happening; revisit when SQLDelight exposes a richer driver API.
        Napier.d("StorageMaintenance(iOS): VACUUM relies on incremental auto-vacuum")
        true
    }

    private fun appendDirectoryEntries(dirPath: String, out: MutableList<CacheEntry>) {
        val contents = fm.contentsOfDirectoryAtPath(dirPath, error = null) ?: return
        for (name in contents) {
            val nameStr = name as? String ?: continue
            val fullPath = (dirPath as NSString).stringByAppendingPathComponent(nameStr)
            val attrs = fm.attributesOfItemAtPath(fullPath, error = null) ?: continue
            val sizeAny = attrs[NSFileSize]
            val createdAny = attrs[NSFileCreationDate]
            val size = (sizeAny as? Number)?.toLong() ?: continue
            val createdDate = createdAny as? NSDate
            val mtimeMs = createdDate?.let { (it.timeIntervalSince1970 * 1000.0).toLong() } ?: 0L
            out += CacheEntry(
                locator = fullPath,
                sizeBytes = size,
                lastModifiedEpochMs = mtimeMs
            )
        }
    }

    companion object {
        const val INBOX_DIR_NAME: String = "kfilesync_inbox"
    }
}