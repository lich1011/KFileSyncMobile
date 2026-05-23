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

// ---- Transfer Context ----

data class TransferCompleted(
    val jobId: JobId,
    val totalBytes: Long,
    override val occurredAt: Instant = Clock.System.now(),
    override val aggregateId: String = jobId.value
) : DomainEvent {
    override val eventType = "transfer.completed"
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