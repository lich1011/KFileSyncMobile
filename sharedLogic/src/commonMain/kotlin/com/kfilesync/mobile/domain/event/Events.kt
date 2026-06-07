package com.kfilesync.mobile.domain.event

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.FileId
import com.kfilesync.mobile.domain.model.JobId
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.VersionVector
import kotlin.time.Clock
import kotlin.time.Instant

/** Marker interface for every domain event published across the bus. */
interface DomainEvent {
    val eventType: String
    val occurredAt: Instant
    val aggregateId: String
}

// ---- Identity Context ----

data class DeviceDiscovered(
    val deviceId: DeviceId,
    val alias: String,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = deviceId.value
) : DomainEvent {
    override val eventType = "device.discovered"
}

data class PairingCompleted(
    val localDevice: DeviceId,
    val peerDevice: DeviceId,
    val pairedAt: Instant = Clock.System.now(),
    override val occurredAt: Instant = pairedAt,
    override val aggregateId: String = peerDevice.value
) : DomainEvent {
    override val eventType = "pairing.completed"
}

data class TrustRevoked(
    val deviceId: DeviceId,
    val revokedAt: Instant = Clock.System.now(),
    override val occurredAt: Instant = revokedAt,
    override val aggregateId: String = deviceId.value
) : DomainEvent {
    override val eventType = "trust.revoked"
}

// ---- Transfer Context (Phase 2) ----

/** Fired the moment a new TransferJob is persisted (either direction). */
data class TransferRequested(
    val jobId: JobId,
    val peerDeviceId: DeviceId,
    val totalBytes: Long,
    val totalFiles: Int,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = jobId.value
) : DomainEvent {
    override val eventType = "transfer.requested"
}

/** Fired on every chunk that is successfully verified + persisted. */
data class TransferProgressAdvanced(
    val jobId: JobId,
    val transferredBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = jobId.value
) : DomainEvent {
    override val eventType = "transfer.progress"
}

data class TransferCompleted(
    val jobId: JobId,
    val totalBytes: Long,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = jobId.value
) : DomainEvent {
    override val eventType = "transfer.completed"
}

data class TransferFailed(
    val jobId: JobId,
    val reason: String,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = jobId.value
) : DomainEvent {
    override val eventType = "transfer.failed"
}


data class ChunkVerificationFailed(
    val jobId: JobId,
    val fileId: FileId,
    val chunkIndex: Int,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = jobId.value
) : DomainEvent {
    override val eventType = "chunk.verification_failed"
}

// ---- Sharing Context (Phase 3) ----

/** Fired when a peer's `POST /share/invite` lands. Surfaces an inbound invitation to the UI. */
data class ShareInvited(
    val shareId: ShareId,
    val shareName: String,
    val fromDeviceId: DeviceId,
    val permission: String,      // wire string: "read_only" | "read_write" | "send_only" | "receive_only"
    val syncMode: String,        // wire string: "one_way_push" | "one_way_pull" | "two_way"
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "share.invited"
}

/** Fired when the user accepts an invitation and the share row + first member are persisted. */
data class ShareAccepted(
    val shareId: ShareId,
    val localPath: String,
    val syncMode: String,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "share.accepted"
}

/** Fired when the user pauses a share - sync engine should stop pushing/pulling chunks for it. */
data class SharePaused(
    val shareId: ShareId,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "share.paused"
}

/** Fired when the user resumes a previously-paused share. */
data class ShareResumed(
    val shareId: ShareId,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "share.resumed"
}

/** Fired when the user leaves a share (or trust to a creator is revoked, cascading). */
data class ShareLeft(
    val shareId: ShareId,
    val reason: String,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "share.left"
}

// ---- Sync Context ----

data class ConflictDetected(
    val shareId: ShareId,
    val filePath: String,
    val localVersion: VersionVector,
    val remoteVersion: VersionVector,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "conflict.detected"
}

/** Fired when a sync session for [shareId] starts - UI lights up the spinner. */
data class SyncStarted(
    val shareId: ShareId,
    val peerDeviceId: DeviceId,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "sync.started"
}

/** Per-step progress - UI shows a textual phase indicator ("Comparing index..."). */
data class SyncProgress(
    val shareId: ShareId,
    val phase: String,            // "index" / "plan" / "transfer" / "finalize"
    val pulled: Int = 0,
    val pushed: Int = 0,
    val conflicts: Int = 0,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "sync.progress"
}

/** Sync session finished successfully. */
data class SyncCompleted(
    val shareId: ShareId,
    val peerDeviceId: DeviceId,
    val pulled: Int,
    val pushed: Int,
    val conflicts: Int,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "sync.completed"
}

/** Sync session aborted before completion. */
data class SyncFailed(
    val shareId: ShareId,
    val reason: String,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = shareId.value
) : DomainEvent {
    override val eventType = "sync.failed"
}