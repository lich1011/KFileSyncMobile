package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.domain.model.TransferState
import com.kfilesync.mobile.domain.port.TransferRepository
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Platform-neutral hook for storage maintenance (T5.5).
 *
 * Concrete adapters live in androidMain / iosMain. Each operation is
 * best-effort: returning false / 0 means "we couldn't do that this time"
 * (e.g. SAF permission revoked, app sandbox locked). The service caller
 * logs and continues - there is no scenario where storage maintenance
 * failure should crash the app.
 *
 * - [deleteCacheFile]: remove a single locator (temp file in app cache).
 * - [listCacheFiles]: enumerate the temp inbox for the cleanup sweep.
 * - [cacheSizeBytes]: total bytes in the app's cache dir - surfaced in
 * the Settings screen and used to decide whether to nuke the cache.
 * - [trimCacheTo]: reduce cache dir to <= [maxBytes] by deleting
 * oldest-first. Returns bytes removed.
 * - [vacuumDatabase]: run `VACUUM` on the SQLite database. Returns true
 * on success, false if the statement failed (e.g. low storage).
 */
interface StorageMaintenanceAdapter {
    suspend fun listCacheFiles(): List<CacheEntry>
    suspend fun deleteCacheFile(locator: String): Boolean
    suspend fun cacheSizeBytes(): Long
    suspend fun trimCacheTo(maxBytes: Long): Long
    suspend fun vacuumDatabase(): Boolean
}

/**
 * One entry from the app's cache dir, for cleanup decisions.
 *
 * - [locator]: opaque path the adapter understands (filesystem path on
 * Android since cache is internal storage; NSURL string on iOS).
 * - [sizeBytes]: file size - used for the cache-trim calculation.
 * - [lastModifiedEpochMs]: write timestamp, used to delete oldest-first.
 */
data class CacheEntry(
    val locator: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long
)

/**
 * Storage maintenance service (T5.5).
 *
 * Three concerns:
 *
 * 1. **Temp-file cleanup**: chunk transfers stage data in the app cache
 * before atomic move. On clean completion the chunk loop calls
 * `FileSink.discard` itself, but a process kill mid-transfer leaves
 * temp files behind. This service walks the cache dir and deletes:
 * - Files associated with `Completed` / `Failed` / `Cancelled` job
 * IDs (the temp serves no purpose).
 * - Files older than [orphanRetention] that no incomplete job
 * references - these are true orphans (the job row was deleted
 * but the temp file survived).
 *
 * 2. **VACUUM**: SQLite's `VACUUM` reclaims space from deleted rows
 * (tombstone purge, completed transfers' chunk-hash blobs, etc).
 * Cheap on a few MB DB; we schedule it daily from the periodic
 * sweeper.
 *
 * 3. **Cache size cap**: enforce a soft limit (default 1 GiB) so a long
 * stream of failed transfers doesn't fill the user's storage. When
 * the cap is hit we trim oldest-first.
 */
class StorageMaintenanceService(
    private val adapter: StorageMaintenanceAdapter,
    private val transferRepository: TransferRepository,
    private val cacheCapBytes: Long = DEFAULT_CACHE_CAP,
    private val orphanRetention: Duration = 24.hours,
    private val clock: () -> kotlin.time.Instant = { Clock.System.now() }
) {

    /**
     * Maintenance summary returned by [sweep] for logging.
     *
     * - [tempFilesDeleted]: chunked temp files removed.
     * - [bytesReclaimedFromCache]: total bytes freed across both the
     * temp-file pass and the size-cap pass.
     * - [vacuumed]: did VACUUM succeed?
     */
    data class SweepReport(
        val tempFilesDeleted: Int,
        val bytesReclaimedFromCache: Long,
        val vacuumed: Boolean
    )

    suspend fun sweep(): SweepReport {
        val now = clock().toEpochMilliseconds()

        // 1. Build the set of "still relevant" job IDs so we can spot orphans.
        val relevantJobIds = runCatching { transferRepository.findIncompleteJobs() }
            .getOrDefault(emptyList())
            .filter { it.state !is TransferState.Completed && it.state !is TransferState.Failed && it.state != TransferState.Cancelled }
            .map { it.id.value }
            .toSet()

        val cacheBefore = runCatching { adapter.cacheSizeBytes() }.getOrDefault(0L)

        // 2. Walk the cache directory and decide per-entry.
        val entries = runCatching { adapter.listCacheFiles() }.getOrDefault(emptyList())
        var deletedFiles = 0
        var deletedBytes = 0L
        for (entry in entries) {
            val ageMs = now - entry.lastModifiedEpochMs
            val refersToActive = relevantJobIds.any { entry.locator.contains(it) }
            val isStale = ageMs > orphanRetention.inWholeMilliseconds
            if (!refersToActive && isStale) {
                val ok = runCatching { adapter.deleteCacheFile(entry.locator) }.getOrDefault(false)
                if (ok) {
                    deletedFiles += 1
                    deletedBytes += entry.sizeBytes
                }
            }
        }

        // 3. Enforce the cache-size cap.
        val capReclaimed = runCatching { adapter.trimCacheTo(cacheCapBytes) }.getOrDefault(0L)

        // 4. VACUUM.
        val vacuumed = runCatching { adapter.vacuumDatabase() }.getOrDefault(false)

        val report = SweepReport(
            tempFilesDeleted = deletedFiles,
            bytesReclaimedFromCache = deletedBytes + capReclaimed,
            vacuumed = vacuumed
        )

        Napier.i(
            "StorageMaintenance: tempFiles=${deletedFiles} reclaimed=${report.bytesReclaimedFromCache} " +
                    "cache=${cacheBefore} -> ${cacheBefore - report.bytesReclaimedFromCache} vacuum=${vacuumed}"
        )
        return report
    }

    /** Surface the current cache size to the UI without doing any deletion. */
    suspend fun cacheSizeBytes(): Long =
        runCatching { adapter.cacheSizeBytes() }.getOrDefault(0L)

    companion object {
        /** 1 GiB default cache cap - sized to fit a long-running multi-job session. */
        const val DEFAULT_CACHE_CAP: Long = 1L * 1024L * 1024L * 1024L
    }
}