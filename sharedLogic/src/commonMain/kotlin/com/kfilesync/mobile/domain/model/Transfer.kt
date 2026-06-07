package com.kfilesync.mobile.domain.model

import com.kfilesync.mobile.domain.DomainError
import com.kfilesync.mobile.domain.service.ChunkingStrategy
import kotlin.time.Clock
import kotlin.time.Instant

/** Globally unique transfer job identifier. */
@kotlin.jvm.JvmInline
value class JobId(val value: String)

/** Per-file identifier inside a [TransferJob]. */
@kotlin.jvm.JvmInline
value class FileId(val value: String)

/** Direction of a transfer relative to the local device. */
enum class TransferDirection { Outgoing, Incoming }

/**
 * Chunk plan for a single file. `chunkSize == 0` means the file is small
 * enough to be sent in a single chunk (see [ChunkingStrategy]); in that
 * case [totalChunks] is 1 and [chunkHashes] holds the single BLAKE3 of
 * the whole file.
 */
data class ChunkManifest(
    val chunkSize: Int,
    val totalChunks: Int,
    val chunkHashes: List<String> // BLAKE3 hex per chunk; size == totalChunks
) {
    init {
        require(chunkHashes.size == totalChunks) {
            "chunkHashes.size (${chunkHashes.size}) must equal totalChunks ($totalChunks)"
        }
        require(totalChunks >= 1) { "totalChunks must be >= 1, got $totalChunks" }
    }
}

/** Aggregate-level progress snapshot. */
data class TransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int
) {
    /** Ratio in `[0.0, 1.0]`. Zero-byte transfers report 1.0 when [completedFiles] == [totalFiles]. */
    val ratio: Float
        get() = when {
            totalBytes > 0L -> (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> completedFiles.toFloat() / totalFiles.toFloat()
            else -> 0f
        }
}

/** Per-file resume checkpoint. `chunksDone` is the count of contiguous-from-zero verified chunks. */
data class Checkpoint(val chunksDone: Int) {
    init { require(chunksDone >= 0) { "chunksDone must be >= 0, got $chunksDone" } }
}

/** Per-file lifecycle status. */
enum class TransferItemStatus { Pending, Active, Verifying, Completed, Failed }

/**
 * Per-file transfer item. Tracks both the static plan ([manifest], [sha256])
 * and the resume cursor ([checkpoint]). [tempPath] is the platform-specific
 * write target while the file is in flight; on completion the receiver
 * atomically renames it into the final location.
 */
data class TransferItem(
    val fileId: FileId,
    val path: String,
    val size: Long,
    val sha256: String,
    val manifest: ChunkManifest,
    val checkpoint: Checkpoint = Checkpoint(0),
    val status: TransferItemStatus = TransferItemStatus.Pending,
    val tempPath: String? = null
) {
    /** True if every chunk has been received and verified. */
    val isComplete: Boolean get() = checkpoint.chunksDone >= manifest.totalChunks

    /** Bytes already accepted from the wire (uses chunkSize math, last chunk may be partial). */
    fun bytesDone(): Long {
        if (manifest.chunkSize == 0) return if (isComplete) size else 0L
        val full = checkpoint.chunksDone.toLong() * manifest.chunkSize.toLong()
        return full.coerceAtMost(size)
    }

    fun withCheckpoint(c: Checkpoint): TransferItem = copy(checkpoint = c)
    fun markActive(): TransferItem = copy(status = TransferItemStatus.Active)
    fun markVerifying(): TransferItem = copy(status = TransferItemStatus.Verifying)
    fun markCompleted(): TransferItem =
        copy(status = TransferItemStatus.Completed, checkpoint = Checkpoint(manifest.totalChunks))
    fun markFailed(): TransferItem = copy(status = TransferItemStatus.Failed)
}

/**
 * Transfer job aggregate root (design doc §6.5.1, Phase 2 T2.1).
 *
 * Invariants:
 * - At least one item; every item has at least one chunk.
 * - State transitions follow [TransferState]'s legal graph; illegal
 * transitions return `Result.failure(DomainError.InvalidStateTransition)`.
 * - `items.size == totalFiles` and `sum(items.size) == totalBytes` -
 * guarded by [requireConsistency].
 *
 * Pure data + pure methods. Persistence is the repository's concern.
 */
data class TransferJob(
    val id: JobId,
    val sessionId: String,
    val peerDeviceId: DeviceId,
    val direction: TransferDirection,
    /** null = direct send/receive; non-null = file belongs to a shared folder (Phase 4). */
    val shareId: ShareId? = null,
    val items: List<TransferItem>,
    val state: TransferState = TransferState.Pending,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val errorMessage: String? = null
) {
    init {
        require(items.isNotEmpty()) { "TransferJob must contain at least one item" }
    }

    val totalBytes: Long get() = items.sumOf { it.size }
    val totalFiles: Int get() = items.size
    val completedFiles: Int get() = items.count { it.isComplete }
    val transferredBytes: Long get() = items.sumOf { it.bytesDone() }

    fun progress(): TransferProgress = TransferProgress(
        transferredBytes = transferredBytes,
        totalBytes = totalBytes,
        completedFiles = completedFiles,
        totalFiles = totalFiles
    )

    /** Legal state transitions; see [TransferState] for the graph. */
    fun start(now: Instant): Result<TransferJob> = when (state) {
        TransferState.Pending,
        is TransferState.Paused -> Result.success(
            copy(state = TransferState.Active(startedAt = now, chunksDone = transferredChunks()), updatedAt = now)
        )
        else -> Result.failure(DomainError.InvalidStateTransition("cannot start from $state"))
    }

    fun pause(now: Instant): Result<TransferJob> = when (state) {
        is TransferState.Active -> Result.success(
            copy(state = TransferState.Paused(Checkpoint(state.chunksDone)), updatedAt = now)
        )
        else -> Result.failure(DomainError.InvalidStateTransition("cannot pause from $state"))
    }

    fun cancel(now: Instant): Result<TransferJob> = when (state) {
        TransferState.Pending,
        is TransferState.Active,
        is TransferState.Paused -> Result.success(copy(state = TransferState.Cancelled, updatedAt = now))
        else -> Result.failure(DomainError.InvalidStateTransition("cannot cancel from $state"))
    }

    fun beginVerifying(now: Instant): Result<TransferJob> = when (state) {
        is TransferState.Active -> Result.success(copy(state = TransferState.Verifying, updatedAt = now))
        else -> Result.failure(DomainError.InvalidStateTransition("cannot verify from $state"))
    }

    fun complete(now: Instant): Result<TransferJob> = when (state) {
        TransferState.Verifying -> Result.success(copy(state = TransferState.Completed(now), updatedAt = now))
        else -> Result.failure(DomainError.InvalidStateTransition("cannot complete from $state"))
    }

    fun fail(reason: String, now: Instant, retries: Int = 0): Result<TransferJob> {
        if (state is TransferState.Completed || state is TransferState.Cancelled) {
            return Result.failure(DomainError.InvalidStateTransition("cannot fail from $state"))
        }
        return Result.success(
            copy(state = TransferState.Failed(reason, retries), errorMessage = reason, updatedAt = now)
        )
    }

    /** Replace one item's checkpoint (after a verified chunk lands). */
    fun withItemCheckpoint(fileId: FileId, checkpoint: Checkpoint, now: Instant): TransferJob {
        val updated = items.map { if (it.fileId == fileId) it.withCheckpoint(checkpoint) else it }
        return copy(items = updated, updatedAt = now)
    }

    /** Replace one item entirely (e.g., marking it Completed after SHA-256 match). */
    fun withItem(fileId: FileId, replacement: TransferItem, now: Instant): TransferJob {
        val updated = items.map { if (it.fileId == fileId) replacement else it }
        return copy(items = updated, updatedAt = now)
    }

    /** Sum of all items' chunksDone - used as the aggregate `chunksDone` field in Active. */
    private fun transferredChunks(): Int = items.sumOf { it.checkpoint.chunksDone }

    companion object {
        /**
         * Factory: build a [TransferJob] for outgoing direct send. The caller
         * supplies the SHA-256 + per-chunk BLAKE3 of each file already
         * computed (background coroutine), plus the chunking [strategy].
         *
         * Each file's chunk count is derived from `(size + chunkSize - 1) / chunkSize`
         * (or 1 when `chunkSize == 0`). The caller must pass a `chunkHashes`
         * list of matching length.
         */
        fun newOutgoing(
            jobId: JobId,
            sessionId: String,
            peer: DeviceId,
            files: List<FilePlan>,
            strategy: ChunkingStrategy,
            createdAt: Instant = Clock.System.now(),
            shareId: ShareId? = null
        ): TransferJob = buildJob(jobId, sessionId, peer, TransferDirection.Outgoing, files, strategy, createdAt, shareId)

        /** Factory: incoming transfer reconstructed from a peer's `TransferRequestDto`. */
        fun newIncoming(
            jobId: JobId,
            sessionId: String,
            peer: DeviceId,
            files: List<FilePlan>,
            strategy: ChunkingStrategy,
            createdAt: Instant = Clock.System.now(),
            shareId: ShareId? = null
        ): TransferJob = buildJob(jobId, sessionId, peer, TransferDirection.Incoming, files, strategy, createdAt, shareId)

        private fun buildJob(
            jobId: JobId,
            sessionId: String,
            peer: DeviceId,
            direction: TransferDirection,
            files: List<FilePlan>,
            strategy: ChunkingStrategy,
            createdAt: Instant,
            shareId: ShareId?
        ): TransferJob {
            require(files.isNotEmpty()) { "TransferJob needs at least one file" }
            val items = files.mapIndexed { idx, plan ->
                val chunkSize = strategy.computeChunkSize(plan.size)
                val totalChunks = if (chunkSize == 0) 1 else
                    ((plan.size + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
                require(plan.chunkHashes.size == totalChunks) {
                    "File ${plan.path}: chunk hash count (${plan.chunkHashes.size}) " +
                            "does not match computed totalChunks ($totalChunks) for size=${plan.size}, chunkSize=$chunkSize"
                }
                TransferItem(
                    fileId = plan.fileId ?: FileId("$idx:${plan.path}"),
                    path = plan.path,
                    size = plan.size,
                    sha256 = plan.sha256,
                    manifest = ChunkManifest(chunkSize, totalChunks, plan.chunkHashes)
                )
            }
            return TransferJob(
                id = jobId,
                sessionId = sessionId,
                peerDeviceId = peer,
                direction = direction,
                shareId = shareId,
                items = items,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        }
    }
}

/**
 * Pre-computed plan for a single file - what the factory needs to build a
 * [TransferItem]. The caller (TransferAppService.sendFiles) does the I/O to
 * compute these in a background coroutine before constructing the job.
 */
data class FilePlan(
    val path: String,
    val size: Long,
    val sha256: String,
    val chunkHashes: List<String>,
    val fileId: FileId? = null
)