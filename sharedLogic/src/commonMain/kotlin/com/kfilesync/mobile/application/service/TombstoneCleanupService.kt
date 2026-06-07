package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.domain.port.FileIndexRepository
import com.kfilesync.mobile.domain.port.ShareRepository
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Periodically purges tombstones older than [retention] (T4.6).
 *
 * Tombstones are kept so a re-create after deletion can't accidentally
 * "resurrect" the old file with a stale version vector - by retaining the
 * deleted entry for 30 days, any device coming back online during that
 * window learns about the deletion through normal sync. After 30 days we
 * assume every device has converged and the row is safe to hard-delete.
 *
 * The service is invoked:
 * - At app start (`sweepNow()` called from bootstrap; cheap).
 * - On every successful sync session (post-sync hook in [SyncServiceImpl]).
 * - Daily via the background scheduler (Phase 4 T4.7).
 *
 * Implementation is intentionally tiny: stateless, single suspend method.
 */
class TombstoneCleanupService(
    private val shareRepository: ShareRepository,
    private val fileIndexRepository: FileIndexRepository,
    private val clock: () -> Instant = { Clock.System.now() },
    private val retention: Duration = 30.days
) {

    suspend fun sweepNow() {
        val cutoff = clock().toEpochMilliseconds() - retention.inWholeMilliseconds
        // Issue #67: cleanupTombstones is a global purge (no share-id),
        // so we call it ONCE and then ask each share for its row counts
        // to compute how much we cleaned. Previously the call was inside
        // the per-share loop, which re-purged the whole DB N times.
        val shares = shareRepository.findAll()
        val beforeCounts = shares.associate { it.id to fileIndexRepository.getTombstones(it.id).size }
        fileIndexRepository.cleanupTombstones(cutoff)

        var purged = 0
        for (share in shares) {
            val after = fileIndexRepository.getTombstones(share.id).size
            purged += ((beforeCounts[share.id] ?: 0) - after).coerceAtLeast(0)
        }

        if (purged > 0) {
            Napier.i("tombstone cleanup purged $purged entries (retention=${retention.inWholeDays}d)")
        }
    }
}