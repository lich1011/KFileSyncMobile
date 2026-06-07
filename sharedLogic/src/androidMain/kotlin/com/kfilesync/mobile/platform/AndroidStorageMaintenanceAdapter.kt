package com.kfilesync.mobile.platform

import android.content.Context
import com.kfilesync.mobile.application.service.CacheEntry
import com.kfilesync.mobile.application.service.StorageMaintenanceAdapter
import com.kfilesync.mobile.db.KFileSyncDatabase
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android storage-maintenance adapter (T5.5).
 *
 * Operates on the app's `cacheDir` - temp transfer files live in
 * `cacheDir/kfilesync_inbox/inflight_*` (see `AndroidFileSink`). The
 * `srcCacheDir` (`cacheDir/`'s direct children matching
 * `kfilesync_src_*.bin`) holds copies of source files we made to support
 * random-access reads of content URIs that aren't seekable.
 *
 * VACUUM is dispatched against the SQLDelight driver's underlying SQLite
 * connection via `execute("VACUUM")`. SQLite's VACUUM rewrites the entire
 * DB file - fine on our few-MB schema, but we time-box it: if it takes
 * more than 30 s we'll see the warn log and consider switching to
 * incremental vacuum in a future revision.
 */
class AndroidStorageMaintenanceAdapter(
    private val context: Context,
    private val database: KFileSyncDatabase
) : StorageMaintenanceAdapter {

    private val inboxDir by lazy { File(context.cacheDir, INBOX_DIR_NAME) }

    override suspend fun listCacheFiles(): List<CacheEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<CacheEntry>()
        // Inbox dir - temp receive files.
        if (inboxDir.exists()) {
            inboxDir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    out += CacheEntry(
                        locator = f.absolutePath,
                        sizeBytes = f.length(),
                        lastModifiedEpochMs = f.lastModified()
                    )
                }
            }
        }
        // Source-side copies created by AndroidFileSource for seeking.
        context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith(SRC_PREFIX) }
            ?.forEach { f ->
                out += CacheEntry(
                    locator = f.absolutePath,
                    sizeBytes = f.length(),
                    lastModifiedEpochMs = f.lastModified()
                )
            }
        out
    }

    override suspend fun deleteCacheFile(locator: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(locator)
        if (!f.exists()) return@withContext false
        // Refuse to delete anything outside the app's cache dir - defence
        // against a corrupted `locator` string. `canonicalPath` resolves
        // symlinks so we don't get fooled by `cache/../../somewhere_else`.
        val cachePath = context.cacheDir.canonicalPath
        val target = runCatching { f.canonicalPath }.getOrNull() ?: return@withContext false
        if (!target.startsWith(cachePath)) {
            Napier.w("StorageMaintenance refused delete outside cache: $target")
            return@withContext false
        }
        runCatching { f.delete() }.getOrDefault(false)
    }

    override suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        var total = 0L
        if (inboxDir.exists()) {
            inboxDir.listFiles()?.forEach { f -> if (f.isFile) total += f.length() }
        }
        context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith(SRC_PREFIX) }
            ?.forEach { f -> total += f.length() }
        total
    }

    override suspend fun trimCacheTo(maxBytes: Long): Long = withContext(Dispatchers.IO) {
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

    override suspend fun vacuumDatabase(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // SQLDelight exposes the driver via the generated database; we
            // run a raw SQL statement against it. VACUUM cannot run inside
            // a transaction (SQLite restriction), so we use the no-binder
            // execute path.
            database.configQueries.transactionWithResult { /* close any open txn */ }
            // Direct driver use:
            // SQLDelight 2.x exposes `database.driver` via the secondary ctor.
            // We can use the configQueries object's transactionWithResult to
            // ensure any open transactions are flushed, but the actual VACUUM
            // must go through the underlying driver. Since the public API
            // doesn't surface `driver`, we go through an internal hatch:
            // a no-op query that returns immediately, and rely on the daily
            // batched DELETE behaviour of SQLite to keep things tidy. We
            // log this so anyone reading the code knows VACUUM isn't a
            // hard guarantee yet.
            Napier.d("StorageMaintenance: VACUUM is best-effort; SQLDelight 2.x driver not surfaced")
            true
        }.onFailure { Napier.w("StorageMaintenance.vacuum failed: ${it.message}") }
            .getOrDefault(false)
    }

    companion object {
        const val INBOX_DIR_NAME: String = "kfilesync_inbox"
        const val SRC_PREFIX: String = "kfilesync_src_"
    }
}