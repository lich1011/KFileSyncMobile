package com.kfilesync.mobile.domain.model

@kotlin.jvm.JvmInline
value class JobId(val value: String)

@kotlin.jvm.JvmInline
value class FileId(val value: String)

data class ChunkManifest(
    val chunkSize: Int,
    val totalChunks: Int,
    val chunkHashes: List<String> // BLAKE3 hex per chunk
)

data class TransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int
)

data class Checkpoint(val chunksDone: Int)

data class TransferItem(
    val fileId: FileId,
    val path: String,
    val size: Long,
    val sha256: String,
    val manifest: ChunkManifest,
    val checkpoint: Checkpoint = Checkpoint(0)
)

/** Transfer Job aggregate root. Phase 2 (T2.1) fills in the factory + state-machine wiring. */
data class TransferJob(
    val id: JobId,
    val sessionId: String,
    val peerDeviceId: DeviceId,
    val items: List<TransferItem>,
    val state: TransferState = TransferState.Pending,
    val createdAt: kotlin.time.Instant
)