package com.kfilesync.mobile.domain.model

data class SyncSession(
    val sessionId: String,
    val shareId: ShareId,
    val peerDeviceId: DeviceId,
    val plan: SyncPlan
)

data class SyncPlan(
    val toPull: List<FileEntry> = emptyList(),
    val toPush: List<FileEntry> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
    val unchanged: List<FileEntry> = emptyList()
)

data class SyncConflict(
    val shareId: ShareId,
    val path: String,
    val local: FileEntry,
    val remote: FileEntry
)

/** Marker for a deleted file kept for 30 days to prevent resurrection. */
data class Tombstone(val shareId: ShareId, val path: String, val deletedAt: kotlin.time.Instant)