package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.db.KFileSyncDatabase
import com.kfilesync.mobile.db.Transfer_items
import com.kfilesync.mobile.db.Transfer_jobs
import com.kfilesync.mobile.domain.model.Checkpoint
import com.kfilesync.mobile.domain.model.ChunkManifest
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.FileId
import com.kfilesync.mobile.domain.model.JobId
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.TransferDirection
import com.kfilesync.mobile.domain.model.TransferItem
import com.kfilesync.mobile.domain.model.TransferItemStatus
import com.kfilesync.mobile.domain.model.TransferJob
import com.kfilesync.mobile.domain.model.TransferProgress
import com.kfilesync.mobile.domain.model.TransferState
import com.kfilesync.mobile.domain.port.TransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * SQLDelight-backed implementation of [TransferRepository] (T2.1 / T2.4).
 *
 * Persistence strategy:
 * - [saveJob] runs an explicit `transaction { ... }`: delete + re-insert
 * every item row, then upsert the header. That keeps the on-disk
 * aggregate atomic with respect to the in-memory one.
 * - [updateProgress] and [updateItemCheckpoint] are single-row updates;
 * they're called on the hot path (once per verified chunk) and must
 * not pay for a full transaction.
 * - `chunk_hashes` is persisted as JSON (a list of hex strings); we use the
 * same Json instance as [SqlDelightDeviceRepo] for consistency. Future
 * schema migrations can move this to a side table if we ever need to query
 * per-hash, but Phase 2 only reads the full list.
 */
class SqlDelightTransferRepo(
    private val db: KFileSyncDatabase,
    private val json: Json = DEFAULT_JSON
) : TransferRepository {

    override suspend fun saveJob(job: TransferJob): Unit = withContext(Dispatchers.Default) {
        db.transaction {
            db.transferJobQueries.insertJob(
                job_id = job.id.value,
                session_id = job.sessionId,
                job_type = jobTypeFromShare(job.shareId),
                direction = directionToWire(job.direction),
                peer_device_id = job.peerDeviceId.value,
                share_id = job.shareId?.value,
                status = stateToWire(job.state),
                total_bytes = job.totalBytes,
                transferred_bytes = job.transferredBytes,
                total_files = job.totalFiles.toLong(),
                completed_files = job.completedFiles.toLong(),
                error_message = job.errorMessage,
                created_at = job.createdAt.toEpochMilliseconds(),
                updated_at = job.updatedAt.toEpochMilliseconds()
            )
            db.transferJobQueries.deleteItemsForJob(job.id.value)
            for (item in job.items) {
                db.transferJobQueries.insertItem(
                    job_id = job.id.value,
                    file_id = item.fileId.value,
                    file_path = item.path,
                    file_size = item.size,
                    sha256 = item.sha256,
                    chunk_size = item.manifest.chunkSize.toLong(),
                    chunk_hashes = encodeChunkHashes(item.manifest.chunkHashes),
                    status = itemStatusToWire(item.status),
                    chunks_total = item.manifest.totalChunks.toLong(),
                    chunks_done = item.checkpoint.chunksDone.toLong(),
                    temp_path = item.tempPath
                )
            }
        }
    }

    override suspend fun updateProgress(jobId: JobId, progress: TransferProgress): Long =
        withContext(Dispatchers.Default) {
            db.transferJobQueries.updateProgress(
                transferred_bytes = progress.transferredBytes,
                completed_files = progress.completedFiles.toLong(),
                status = if (progress.completedFiles >= progress.totalFiles) "completed" else "active",
                updated_at = Clock.System.now().toEpochMilliseconds(),
                job_id = jobId.value
            ).value
        }

    override suspend fun updateItemCheckpoint(
        jobId: JobId,
        fileId: FileId,
        chunksDone: Int,
        status: String
    ): Long = withContext(Dispatchers.Default) {
        db.transferJobQueries.updateItemProgress(
            chunks_done = chunksDone.toLong(),
            status = status,
            job_id = jobId.value,
            file_id = fileId.value
        ).value
    }

    override suspend fun findIncompleteJobs(): List<TransferJob> = withContext(Dispatchers.Default) {
        // Phase 6 (T6.2): use the batched item loader to avoid N+1.
        val headers = db.transferJobQueries.findIncomplete().executeAsList()
        val itemsByJob = groupAllItemsByJob()
        headers.map { it.toDomain(itemsByJob[it.job_id].orEmpty()) }
    }

    override suspend fun findById(jobId: JobId): TransferJob? = withContext(Dispatchers.Default) {
        val header = db.transferJobQueries.findById(jobId.value).executeAsOneOrNull() ?: return@withContext null
        // Single-job lookup keeps the per-job item query - it's already one
        // round-trip and the batched loader would over-fetch.
        header.toDomain(itemsFor(header.job_id))
    }

    override suspend fun findAll(): List<TransferJob> = withContext(Dispatchers.Default) {
        // Phase 6 (T6.2): batch the item-table load. The old code ran
        // `getItemsForJob` once per row (N+1); for users with hundreds of
        // historical transfers that's hundreds of queries per refresh of
        // the Transfers tab. With the batched loader we hit the items table
        // once + a group-by client side.
        val headers = db.transferJobQueries.findAll().executeAsList()
        if (headers.isEmpty()) return@withContext emptyList()
        val itemsByJob = groupAllItemsByJob()
        headers.map { it.toDomain(itemsByJob[it.job_id].orEmpty()) }
    }

    override suspend fun delete(jobId: JobId) = withContext(Dispatchers.Default) {
        db.transaction {
            // FK cascade handles the items table.
            db.transferJobQueries.deleteJob(jobId.value)
        }
    }

    // -------- row -> domain mapping --------

    private fun itemsFor(jobIdValue: String): List<Transfer_items> =
        db.transferJobQueries.getItemsForJob(jobIdValue).executeAsList()

    /**
     * Phase 6 (T6.2): one-shot load of every `transfer_items` row, grouped
     * by `job_id`. Used by [findAll] / [findIncompleteJobs] to avoid the
     * old N+1 pattern. Cost is one query + an O(items) groupBy. Linear in
     * total item count vs old O(jobs * items_per_job) query count.
     */
    private fun groupAllItemsByJob(): Map<String, List<Transfer_items>> =
        db.transferJobQueries.getAllItems()
            .executeAsList()
            .groupBy { it.job_id }

    private fun Transfer_jobs.toDomain(itemRows: List<Transfer_items>): TransferJob {
        val items = itemRows.map { it.toDomain() }
        val state = wireToState(status, error_message, Instant.fromEpochMilliseconds(updated_at))
        return TransferJob(
            id = JobId(job_id),
            sessionId = session_id,
            peerDeviceId = DeviceId(peer_device_id),
            direction = directionFromWire(direction),
            shareId = share_id?.let { ShareId(it) },
            items = items,
            state = state,
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            errorMessage = error_message
        )
    }

    private fun Transfer_items.toDomain(): TransferItem {
        val hashes = decodeChunkHashes(chunk_hashes)
        val total = chunks_total.toInt().coerceAtLeast(1)
        // Defensive: if the stored hash list ever drifts from chunks_total (bug,
        // partial write), pad/truncate so ChunkManifest's invariant holds.
        val safeHashes = when {
            hashes.size == total -> hashes
            hashes.size > total -> hashes.take(total)
            else -> hashes + List(total - hashes.size) { "" }
        }
        return TransferItem(
            fileId = FileId(file_id),
            path = file_path,
            size = file_size,
            sha256 = sha256,
            manifest = ChunkManifest(chunk_size.toInt(), total, safeHashes),
            checkpoint = Checkpoint(chunks_done.toInt()),
            status = itemStatusFromWire(status),
            tempPath = temp_path
        )
    }

    private fun encodeChunkHashes(hashes: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), hashes)

    private fun decodeChunkHashes(raw: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
            .getOrDefault(emptyList())

    companion object {
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    // -------- enum <=> wire string --------

    private fun directionToWire(d: TransferDirection): String = when (d) {
        TransferDirection.Outgoing -> "outgoing"
        TransferDirection.Incoming -> "incoming"
    }

    private fun directionFromWire(v: String): TransferDirection = when (v) {
        "outgoing" -> TransferDirection.Outgoing
        "incoming" -> TransferDirection.Incoming
        else -> TransferDirection.Incoming
    }

    private fun jobTypeFromShare(shareId: ShareId?): String = if (shareId == null) "direct" else "share"

    private fun stateToWire(state: TransferState): String = when (state) {
        is TransferState.Pending -> "pending"
        is TransferState.Active -> "active"
        is TransferState.Paused -> "paused"
        is TransferState.Verifying -> "verifying"
        is TransferState.Completed -> "completed"
        is TransferState.Failed -> "failed"
        is TransferState.Cancelled -> "cancelled"
    }

    private fun wireToState(
        status: String,
        errorMessage: String?,
        updatedAt: Instant
    ): TransferState = when (status) {
        "pending" -> TransferState.Pending
        // We don't persist chunksDone for the in-flight state machine - the
        // aggregate recomputes it from per-item checkpoints on rehydration.
        "active" -> TransferState.Active(startedAt = updatedAt, chunksDone = 0)
        "paused" -> TransferState.Paused(Checkpoint(0))
        "verifying" -> TransferState.Verifying
        "completed" -> TransferState.Completed(updatedAt)
        "failed" -> TransferState.Failed(errorMessage ?: "unknown", retries = 0)
        "cancelled" -> TransferState.Cancelled
        else -> TransferState.Pending
    }

    private fun itemStatusToWire(s: TransferItemStatus): String = when (s) {
        TransferItemStatus.Pending -> "pending"
        TransferItemStatus.Active -> "active"
        TransferItemStatus.Verifying -> "verifying"
        TransferItemStatus.Completed -> "completed"
        TransferItemStatus.Failed -> "failed"
    }

    private fun itemStatusFromWire(v: String): TransferItemStatus = when (v) {
        "pending" -> TransferItemStatus.Pending
        "active" -> TransferItemStatus.Active
        "verifying" -> TransferItemStatus.Verifying
        "completed" -> TransferItemStatus.Completed
        "failed" -> TransferItemStatus.Failed
        else -> TransferItemStatus.Pending
    }
}