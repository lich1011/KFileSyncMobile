package com.kfilesync.mobile.domain.port

import app.cash.sqldelight.db.QueryResult
import com.kfilesync.mobile.domain.model.BlockLocation
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceAddress
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.JobId
import com.kfilesync.mobile.domain.model.PairingSession
import com.kfilesync.mobile.domain.model.Share
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareMember
import com.kfilesync.mobile.domain.model.TransferJob
import com.kfilesync.mobile.domain.model.TransferProgress
import com.kfilesync.mobile.domain.model.TrustStatus
import kotlin.time.Instant

// -------- Storage ports (driven side of the hexagon) --------

/**
 * Device repository. Stores known devices and their trust state.
 *
 * Phase 1 (T1.6) extends this with [findAll], [updateLastSeen], and [delete]
 * to support the heartbeat (T1.9) and trust-revocation (T1.4) flows.
 */
interface DeviceRepository {
    suspend fun findById(id: DeviceId): Device?
    suspend fun findPaired(): List<Device>

    /** Every non-revoked device (Discovered ∪ Paired). Used by the Devices UI. */
    suspend fun findAll(): List<Device>
    suspend fun save(device: Device): QueryResult<Long>
    suspend fun updateTrustStatus(id: DeviceId, status: TrustStatus): QueryResult<Long>

    /**
     * Refresh the last-seen timestamp and (optionally) the address list for a
     * known device. Called by the heartbeat (T1.9) on every successful ping
     * and by the discovery adapters (T1.2) when a new advertisement arrives.
     */
    suspend fun updateLastSeen(id: DeviceId, lastSeenAt: Instant, addresses: List<DeviceAddress>?): QueryResult<Long>

    /** Hard-delete a device record (used by revoke + manual cleanup). */
    suspend fun delete(id: DeviceId): QueryResult<Long>
}

interface ShareRepository {
    suspend fun findById(id: ShareId): Share?
    suspend fun findByMember(deviceId: DeviceId): List<Share>
    suspend fun save(share: Share)
    suspend fun addMember(shareId: ShareId, member: ShareMember)
    suspend fun removeMember(shareId: ShareId, deviceId: DeviceId)
}

interface FileIndexRepository {
    suspend fun getIndex(shareId: ShareId): List<FileEntry>
    suspend fun getIncremental(shareId: ShareId, sinceVersion: Long): List<FileEntry>
    suspend fun upsertEntry(entry: FileEntry)
    suspend fun upsertEntriesBatch(entries: List<FileEntry>)
    suspend fun findBlocksByHash(hash: String): List<BlockLocation>
}

interface TransferRepository {
    suspend fun saveJob(job: TransferJob)
    suspend fun updateProgress(jobId: JobId, progress: TransferProgress)
    suspend fun findIncompleteJobs(): List<TransferJob>
}

/**
 * Pairing session repository (T1.6).
 *
 * Pairing sessions live in the database for two reasons:
 * 1. Survive a process restart mid-handshake (rare but real on mobile).
 * 2. Provide a single source of truth that both the inbound HTTP route
 * and the outbound UI flow can read concurrently.
 *
 * A pending session is short-lived (5 min); [cleanupExpired] is called from
 * the application service at boot to drop stale rows.
 */
interface PairingRequestRepository {
    suspend fun save(session: PairingSession): QueryResult<Long>
    suspend fun findById(sessionId: String): PairingSession?
    suspend fun findPending(now: Instant): List<PairingSession>
    suspend fun cleanupExpired(now: Instant): QueryResult<Long>
}