package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.model.TransferState
import com.kfilesync.mobile.domain.port.DirectoryScanner
import com.kfilesync.mobile.domain.port.FileIndexRepository
import com.kfilesync.mobile.domain.port.ShareRepository
import com.kfilesync.mobile.domain.port.TransferRepository
import io.github.aakira.napier.Napier
import kotlin.time.Clock

/**
 * Recovery report (T5.2). Each field is a count so the caller can log a
 * single-line summary without iterating the underlying lists.
 *
 * Returned to the bootstrap so a Napier line can prove recovery happened
 * and how much work it found.
 */
data class RecoveryReport(
    val transferJobsResumed: Int,
    val transferJobsAbandoned: Int,
    val shareIndexInconsistencies: Int,
    val tombstonesRecorded: Int
)

/**
 * Crash recovery service (T5.2).
 *
 * Runs once at app start after the DI graph is up. Three concerns:
 *
 * 1. **Interrupted transfers** - re-hydrate Active / Paused / Verifying
 * jobs. The Phase 2 TransferService.resumeAfterRestart() already
 * surfaces these via its UI flow, but we additionally:
 * - Demote `TransferState.Verifying` rows back to `Active` because
 * verification is non-resumable: an interrupted verify must restart
 * from the file's first chunk. The next chunk POST will quickly
 * re-flip it to Verifying once the last chunk completes.
 * - Abandon jobs older than 24 h that are still `Pending` - those are
 * almost certainly stale (peer never came back) and would otherwise
 * clog the UI.
 *
 * 2. **Index <-> filesystem consistency** - for each Active share, ask the
 * [DirectoryScanner] for the live tree size and compare it against
 * `getIndex(shareId).size`. A big mismatch means the user deleted /
 * restored files while we were down; we log it so the next indexer
 * pass runs with extra logging. We DON'T tombstone aggressively here
 * - that's the indexer's job, and rushing it from recovery would
 * interfere with users who legitimately moved their share dir.
 *
 * 3. **Tombstone retention** - purely defensive: confirm that the
 * tombstone count hasn't exploded since last shutdown. This is the
 * same job [TombstoneCleanupService.sweepNow] does - we just count
 * before/after for the report.
 *
 * The service is intentionally read-mostly: the only writes are the
 * Verifying -> Active demotions. Heavy work (full rehash, conflict
 * resolution) is deferred to the indexer + sync pipeline.
 */
class CrashRecoveryService(
    private val transferRepository: TransferRepository,
    private val shareRepository: ShareRepository,
    private val fileIndexRepository: FileIndexRepository,
    private val directoryScanner: DirectoryScanner,
    private val tombstoneCleanup: TombstoneCleanupService,
    private val clock: () -> kotlin.time.Instant = { Clock.System.now() }
) {

    suspend fun recover(): RecoveryReport {
        var resumed = 0
        var abandoned = 0
        var inconsistencies = 0

        // ---- 1. interrupted transfers ----
        val incomplete = transferRepository.findIncompleteJobs()
        val now = clock()
        for (job in incomplete) {
            when (val state = job.state) {
                is TransferState.Verifying -> {
                    // Demote to Active so the resume-skipChunks logic on the
                    // sender side can repopulate the verifier when the last
                    // chunk re-arrives. Persist the demoted state.
                    //
                    // chunksDone is recomputed from the sum of per-item
                    // checkpoints - that's the only honest value we have
                    // post-restart (the aggregate's 'chunksDone' field on
                    // Active is not persisted; see SqlDelightTransferRepo).
                    val chunksDone = job.items.sumOf { it.checkpoint.chunksDone }
                    val demoted = job.copy(
                        state = TransferState.Active(startedAt = now, chunksDone = chunksDone),
                        updatedAt = now
                    )
                    runCatching { transferRepository.saveJob(demoted) }
                        .onFailure { Napier.w("CrashRecovery: demote-verifying failed for ${job.id.value}: ${it.message}") }
                    resumed += 1
                }

                is TransferState.Active, is TransferState.Paused -> {
                    resumed += 1
                }

                is TransferState.Pending -> {
                    val ageMs = now.toEpochMilliseconds() - job.createdAt.toEpochMilliseconds()
                    if (ageMs > STALE_PENDING_MS) {
                        runCatching { transferRepository.delete(job.id) }
                            .onFailure { Napier.w("CrashRecovery: abandon-pending failed for ${job.id.value}: ${it.message}") }
                        abandoned += 1
                    } else {
                        resumed += 1
                    }
                }

                else -> {
                    // Completed / Failed / Cancelled - finalised states.
                    // findIncompleteJobs() should not return these, but a
                    // belt-and-braces no-op is harmless if it does.
                }
            }
        }

        // ---- 2. index <-> filesystem consistency ----
        val activeShares = runCatching { shareRepository.findAll() }.getOrDefault(emptyList())
            .filter { it.status == ShareStatus.Active && it.localPath.isNotBlank() }

        for (share in activeShares) {
            val live = runCatching { directoryScanner.scan(share.localPath) }.getOrNull()
                ?: continue // SAF URI maybe revoked; defer to indexer

            val indexedCount = runCatching { fileIndexRepository.getIndex(share.id).size }
                .getOrDefault(0)

            val drift = kotlin.math.abs(live.size - indexedCount)
            // Tolerate small drift (a few writes between snapshot and shutdown).
            // Beyond 'DRIFT_THRESHOLD' we record an inconsistency so the next
            // indexer pass can decide what to do - we do NOT modify the index
            // here, that would race with a possible concurrent sync attempt.
            if (drift > DRIFT_THRESHOLD) {
                inconsistencies += 1
                Napier.w(
                    "CrashRecovery: share ${share.id.value.take(8)} drift - " +
                            "live=${live.size} indexed=$indexedCount delta=$drift"
                )
            }
        }

        // ---- 3. tombstone retention ----
        val priorTombstones = activeShares.sumOf {
            runCatching { fileIndexRepository.getTombstones(it.id).size }.getOrDefault(0)
        }

        runCatching { tombstoneCleanup.sweepNow() }
            .onFailure { Napier.w("CrashRecovery: tombstone sweep failed: ${it.message}") }

        val nextTombstones = activeShares.sumOf {
            runCatching { fileIndexRepository.getTombstones(it.id).size }.getOrDefault(0)
        }

        val tombstoneDelta = (priorTombstones - nextTombstones).coerceAtLeast(0)

        val report = RecoveryReport(
            transferJobsResumed = resumed,
            transferJobsAbandoned = abandoned,
            shareIndexInconsistencies = inconsistencies,
            tombstonesRecorded = tombstoneDelta
        )

        Napier.i(
            "CrashRecovery: transfers resumed=$resumed abandoned=$abandoned " +
                    "shareDrift=$inconsistencies tombstonesPurged=$tombstoneDelta"
        )

        return report
    }

    companion object {
        /** Pending transfers older than this are abandoned at recovery time. */
        const val STALE_PENDING_MS: Long = 24L * 60L * 60L * 1000L

        /** Indexer-vs-filesystem entry-count tolerance. */
        const val DRIFT_THRESHOLD: Int = 5
    }
}