package com.kfilesync.mobile.domain.port

import com.kfilesync.mobile.domain.model.BlockLocation
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceAddress
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.FileId
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

    /** Every non-revoked device (Discovered u Paired). Used by the Devices UI. */
    suspend fun findAll(): List<Device>

    suspend fun save(device: Device): Long

    suspend fun updateTrustStatus(id: DeviceId, status: TrustStatus): Long

    /**
     * Refresh the last-seen timestamp and (optionally) the address list for a
     * known device. Called by the heartbeat (T1.9) on every successful ping
     * and by the discovery adapters (T1.2) when a new advertisement arrives.
     */
    suspend fun updateLastSeen(id: DeviceId, lastSeenAt: Instant, addresses: List<DeviceAddress>?): Long

    /** Hard-delete a device record (used by revoke + manual cleanup). */
    suspend fun delete(id: DeviceId): Long
}

interface ShareRepository {
    suspend fun findById(id: ShareId): Share?

    suspend fun findByMember(deviceId: DeviceId): List<Share>

    /** Every share (any status). Used by the Shares tab to compose active/pending/paused/left sections. */
    suspend fun findAll(): List<Share>

    /** Filter by status - e.g. 'Pending' for the invitations banner. */
    suspend fun findByStatus(status: String): List<Share>

    /** Upsert: full snapshot of the aggregate (members included). */
    suspend fun save(share: Share)

    /** Targeted update to status only - hot path for pause/resume/leave. */
    suspend fun updateStatus(shareId: ShareId, status: String)

    /** Targeted update used by accept(): sets localPath and flips to Active in one write. */
    suspend fun updateLocalPath(shareId: ShareId, localPath: String, status: String)

    suspend fun addMember(shareId: ShareId, member: ShareMember)

    suspend fun removeMember(shareId: ShareId, deviceId: DeviceId)

    /** Hard-delete a share + its membership rows (cascade via FK). */
    suspend fun delete(shareId: ShareId)

    /** Cascade-cleanup hook: drop every membership row a given device holds. */
    suspend fun removeMembershipsForDevice(deviceId: DeviceId)
}

interface FileIndexRepository {
    /** Live entries for a share (deleted = 0). */
    suspend fun getIndex(shareId: ShareId): List<FileEntry>

    /** Entries updated since [sinceEpochMs] (deleted or not). Phase 4 incremental sync hook. */
    suspend fun getIncremental(shareId: ShareId, sinceEpochMs: Long): List<FileEntry>

    /** Single-entry lookup by (shareId, path). Returns null if missing. */
    suspend fun findByPath(shareId: ShareId, path: String): FileEntry?

    /** Tombstones currently retained (deleted = 1). UI / cleanup task uses this. */
    suspend fun getTombstones(shareId: ShareId): List<FileEntry>

    suspend fun upsertEntry(entry: FileEntry)

    suspend fun upsertEntriesBatch(entries: List<FileEntry>)

    /** Hard-delete entries whose [FileEntry.deletedAt] is older than the cutoff (T4.6 cleanup). */
    suspend fun cleanupTombstones(olderThanEpochMs: Long): Long

    /** Resolve a chunk's BLAKE3 hex to the blocks holding it - used by `/sync/blocks` to dedupe content across files. */
    suspend fun findBlocksByHash(hash: String): List<BlockLocation>
}

/**
 * Transfer-job repository (T2.1 / T2.4).
 *
 * Persists the aggregate root + every item row. The aggregate is rebuilt by
 * joining 'transfer_jobs' and 'transfer_items' on 'job_id'; callers should
 * always go through [saveJob] (full upsert) rather than poking individual
 * item rows so the in-memory aggregate and the DB stay in lock-step.
 */
interface TransferRepository {
    /** Full upsert: writes the header row + every item row in one transaction. */
    suspend fun saveJob(job: TransferJob)

    /** Fast progress write - used after every verified chunk; doesn't touch items. */
    suspend fun updateProgress(jobId: JobId, progress: TransferProgress): Long

    /** Per-item checkpoint write - used after every verified chunk. */
    suspend fun updateItemCheckpoint(jobId: JobId, fileId: FileId, chunksDone: Int, status: String): Long

    /** Returns Pending/Active/Paused jobs - used for resume after crash. */
    suspend fun findIncompleteJobs(): List<TransferJob>

    /** Single-job fetch - used by the HTTP routes when chunks come in. */
    suspend fun findById(jobId: JobId): TransferJob?

    /** Returns every job (UI list, no filter). Ordered most-recent first. */
    suspend fun findAll(): List<TransferJob>

    /** Hard-delete a job + its items (cascade via FK). */
    suspend fun delete(jobId: JobId)
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
    suspend fun save(session: PairingSession): Long

    suspend fun findById(sessionId: String): PairingSession?

    suspend fun findPending(now: Instant): List<PairingSession>

    suspend fun cleanupExpired(now: Instant): Long
}