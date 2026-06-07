package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.application.dto.toDomain
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.DomainError
import com.kfilesync.mobile.domain.event.ConflictDetected
import com.kfilesync.mobile.domain.event.SyncCompleted
import com.kfilesync.mobile.domain.event.SyncFailed
import com.kfilesync.mobile.domain.event.SyncProgress
import com.kfilesync.mobile.domain.event.SyncStarted
import com.kfilesync.mobile.domain.event.TransferCompleted
import com.kfilesync.mobile.domain.event.TransferFailed
import com.kfilesync.mobile.domain.model.ConflictResolution
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.Share
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.model.SyncConflict
import com.kfilesync.mobile.domain.model.SyncPlan
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.DirectoryScanner
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.FileIndexRepository
import com.kfilesync.mobile.domain.port.PlatformFile
import com.kfilesync.mobile.domain.port.ShareRepository
import com.kfilesync.mobile.domain.service.ConflictResolver
import com.kfilesync.mobile.domain.service.Indexer
import com.kfilesync.mobile.domain.service.PolicyEnforcer
import com.kfilesync.mobile.domain.service.SyncPlanGenerator
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Lightweight UI status snapshot for the "sync" tab. */
data class SyncStatus(
    val running: Boolean,
    val currentShare: ShareId? = null,
    val currentPeer: DeviceId? = null,
    val pendingConflicts: Int = 0,
    val lastError: String? = null,
    val lastSyncedAt: Instant? = null
)

/**
 * Driving port: sync orchestration (design doc §6.2.1 + Phase 4 T4.3 - T4.7).
 */
interface SyncAppService {
    /**
     * Run a one-shot sync for [shareId] against [peerId]. Returns a Result
     * with the final SyncPlan on success, or a domain/infra error.
     */
    suspend fun syncNow(shareId: ShareId, peerId: DeviceId): Result<SyncPlan>

    /**
     * Sync the share against every paired peer that's a member. Convenience
     * wrapper around [syncNow]. Best-effort: failures on one peer don't abort
     * other peers.
     */
    suspend fun syncShare(shareId: ShareId): Result<Unit>

    /** Resolve a pending conflict surfaced via [observeConflicts]. */
    suspend fun resolveConflict(shareId: ShareId, path: String, resolution: ConflictResolution): Result<Unit>

    fun observeSyncStatus(): StateFlow<SyncStatus>
    fun observeConflicts(): Flow<List<SyncConflict>>
}

/**
 * Phase 4 implementation (T4.3 - T4.5).
 *
 * Six-step sync flow (design doc §14):
 *
 * 1. **Permission verification** - [PolicyEnforcer.canPush] / `canPull`.
 * If neither direction is allowed (e.g. share Paused), abort early.
 * 2. **Local index refresh** - run [Indexer.fullScan] so we don't push
 * stale rows. Cheap on no-op (mtime+size quick-check skips rehash).
 * 3. **Index exchange** - fetch the peer's index via `GET /sync/index`.
 * 4. **Plan generation** - [SyncPlanGenerator] diffs both indexes.
 * Conflicts go to a pending queue surfaced through [observeConflicts].
 * 5. **Block transfer** - for every `toPush` entry, hand it to
 * [TransferAppService.sendFiles] with `shareId != null` so the wire
 * DTO carries the share context. Pulls are realized by the peer's
 * symmetric push that lands in our `/transfer/chunks` route.
 * 6. **Event publishing** - [SyncProgress] per phase, [SyncCompleted]
 * on success or [SyncFailed] on abort.
 *
 * Mutual exclusion: at most one sync session per `(shareId)` at a time -
 * the `inFlight` set is the gate. Two peers can sync the same share
 * concurrently if the share has 3+ members.
 *
 * Pull semantics: this implementation pushes proactively; pulls happen
 * naturally when the *peer* runs the same algorithm and pushes its own
 * `toPush` (which is our `toPull`). The first sync after pairing
 * therefore exchanges files in both directions - each side pushes what
 * the other lacks.
 */
class SyncServiceImpl(
    private val shareRepository: ShareRepository,
    private val deviceRepository: DeviceRepository,
    private val fileIndexRepository: FileIndexRepository,
    private val policyEnforcer: PolicyEnforcer,
    private val indexerFactory: suspend (Share) -> Indexer,
    private val syncPlanGenerator: SyncPlanGenerator,
    private val conflictResolver: ConflictResolver,
    private val transferService: TransferAppService,
    private val httpClient: LanSyncHttpClient,
    private val directoryScanner: DirectoryScanner,
    private val localIdentityProvider: LocalIdentityProvider,
    private val eventBus: EventBus,
    private val clock: () -> Instant = { Clock.System.now() }
) : SyncAppService {

    private val statusFlow = MutableStateFlow(SyncStatus(running = false))
    private val conflictsFlow = MutableStateFlow<List<SyncConflict>>(emptyList())

    /** Set of (shareId, peerId) pairs currently syncing - prevents double-runs. */
    private val inFlight = mutableSetOf<Pair<ShareId, DeviceId>>()
    private val gateLock = Mutex()

    override fun observeSyncStatus(): StateFlow<SyncStatus> = statusFlow.asStateFlow()

    override fun observeConflicts(): Flow<List<SyncConflict>> = conflictsFlow.asStateFlow()

    override suspend fun syncNow(shareId: ShareId, peerId: DeviceId): Result<SyncPlan> {
        // ---- Issue #29: share-status gate ----
        // Direct callers (UI "Sync now" button, BG tasks) must not bypass the
        // status check that [syncShare] already does. A Paused / Left share
        // shouldn't generate transfers; doing so leaks files through a state
        // the user explicitly disabled.
        val gateShare = shareRepository.findById(shareId)
            ?: return Result.failure(DomainError.ShareNotFound(shareId))

        if (gateShare.status != ShareStatus.Active) {
            return Result.failure(
                DomainError.InvalidStateTransition("share not active, was ${gateShare.status}")
            )
        }

        // ---- Gate ----
        val gateKey = shareId to peerId
        gateLock.withLock {
            if (gateKey in inFlight) {
                return Result.failure(DomainError.InvalidStateTransition("sync already in flight for share/peer"))
            }
            inFlight += gateKey
        }

        statusFlow.value = SyncStatus(
            running = true,
            currentShare = shareId,
            currentPeer = peerId,
            pendingConflicts = conflictsFlow.value.size
        )
        eventBus.publish(SyncStarted(shareId = shareId, peerDeviceId = peerId))

        return try {
            val plan = runSyncSession(shareId, peerId)
            statusFlow.value = SyncStatus(
                running = false,
                pendingConflicts = conflictsFlow.value.size,
                lastSyncedAt = clock()
            )
            eventBus.publish(
                SyncCompleted(
                    shareId = shareId,
                    peerDeviceId = peerId,
                    pulled = plan.toPull.size,
                    pushed = plan.toPush.size,
                    conflicts = plan.conflicts.size
                )
            )
            Result.success(plan)
        } catch (t: Throwable) {
            val msg = t.message ?: t::class.simpleName ?: "sync failed"
            statusFlow.value = SyncStatus(
                running = false,
                pendingConflicts = conflictsFlow.value.size,
                lastError = msg
            )
            eventBus.publish(SyncFailed(shareId = shareId, reason = msg))
            Napier.w("syncNow($shareId, $peerId) failed: $msg", t)
            Result.failure(t)
        } finally {
            gateLock.withLock { inFlight -= gateKey }
        }
    }

    override suspend fun syncShare(shareId: ShareId): Result<Unit> {
        val share = shareRepository.findById(shareId)
            ?: return Result.failure(DomainError.ShareNotFound(shareId))

        if (share.status != ShareStatus.Active) {
            return Result.failure(DomainError.InvalidStateTransition("share not active, was ${share.status}"))
        }

        val me = localIdentityProvider.current().deviceId
        val targets = share.members
            .map { it.deviceId }
            .filter { it != me }
            .filter { deviceRepository.findById(it)?.state is DeviceState.Paired }

        var anyFailed = false
        for (peer in targets) {
            val result = syncNow(shareId, peer)
            if (result.isFailure) anyFailed = true
        }

        return if (anyFailed) Result.failure(RuntimeException("one or more peers failed to sync"))
        else Result.success(Unit)
    }

    override suspend fun resolveConflict(
        shareId: ShareId,
        path: String,
        resolution: ConflictResolution
    ): Result<Unit> {
        val conflict = conflictsFlow.value.firstOrNull { it.shareId == shareId && it.path == path }
            ?: return Result.failure(DomainError.InvalidStateTransition("no pending conflict for $path"))

        val me = localIdentityProvider.current().deviceId
        val resolved = conflictResolver.resolve(conflict, resolution, me, clock())

        // Persist primary + optional conflict copy.
        fileIndexRepository.upsertEntry(resolved.primary)
        resolved.conflictCopy?.let { fileIndexRepository.upsertEntry(it) }

        // Remove the conflict from the queue.
        conflictsFlow.value = conflictsFlow.value.filterNot { it.shareId == shareId && it.path == path }
        statusFlow.value = statusFlow.value.copy(pendingConflicts = conflictsFlow.value.size)
        return Result.success(Unit)
    }

    // -------- pipeline --------

    private suspend fun runSyncSession(shareId: ShareId, peerId: DeviceId): SyncPlan {
        // Step 1: permission verification.
        val share = shareRepository.findById(shareId)
            ?: throw DomainError.ShareNotFound(shareId)
        if (share.status != ShareStatus.Active) {
            throw DomainError.InvalidStateTransition("share not active, was ${share.status}")
        }
        if (share.localPath.isBlank()) {
            throw DomainError.InvalidStateTransition("share has no localPath")
        }

        val canPush = policyEnforcer.canPush(peerId, shareId)
        val canPull = policyEnforcer.canPull(peerId, shareId)
        if (!canPush && !canPull) {
            throw DomainError.PermissionDenied("policy forbids both push and pull for peer/share")
        }

        // Step 2: local index refresh.
        eventBus.publish(SyncProgress(shareId, phase = "index"))
        val me = localIdentityProvider.current().deviceId
        val indexer = indexerFactory(share)
        indexer.fullScan(shareId, share.localPath, me)
        val localIndex = fileIndexRepository.getIndex(shareId) + fileIndexRepository.getTombstones(shareId)

        // Step 3: index exchange.
        val peer = deviceRepository.findById(peerId)
            ?: throw DomainError.DeviceNotFound(peerId)
        val peerAddr = peer.addresses.firstOrNull()
            ?: throw DomainError.InvalidStateTransition("peer has no known address")
        val baseUrl = "https://${peerAddr.host}:${peerAddr.port}"
        val remoteDto = httpClient.getSyncIndex(baseUrl, shareId.value)
            ?: throw DomainError.InvalidStateTransition("peer /sync/index returned null")
        val now = clock()
        val remoteIndex = remoteDto.entries.map { it.toDomain(shareId, now) }

        // Step 4: plan generation.
        eventBus.publish(SyncProgress(shareId, phase = "plan", pushed = 0, pulled = 0, conflicts = 0))
        val plan = syncPlanGenerator.generate(localIndex, remoteIndex)

        // Push conflicts to the queue and emit per-conflict events so the UI
        // can light up the conflict badge without waiting for sync completion.
        if (plan.conflicts.isNotEmpty()) {
            conflictsFlow.value = mergeConflicts(conflictsFlow.value, plan.conflicts)
            for (c in plan.conflicts) {
                eventBus.publish(
                    ConflictDetected(
                        shareId = shareId,
                        filePath = c.path,
                        localVersion = c.local.versionVector,
                        remoteVersion = c.remote.versionVector
                    )
                )
            }
        }

        // Step 5: block transfer.
        eventBus.publish(SyncProgress(shareId, phase = "transfer", pushed = 0, pulled = plan.toPull.size, conflicts = plan.conflicts.size))

        // Push half: send every toPush entry we're allowed to push.
        if (canPush) {
            val pushable = plan.toPush.filterNot { it.deleted }
            if (pushable.isNotEmpty()) {
                val files = pushable.mapNotNull { entry ->
                    val scanned = directoryScanner.resolve(share.localPath, entry.path) ?: return@mapNotNull null
                    PlatformFile(
                        displayName = entry.path,
                        sizeBytes = entry.size,
                        locator = scanned.locator
                    )
                }

                if (files.isNotEmpty()) {
                    // Issue #27: previously this was fire-and-forget, but
                    // [SyncCompleted] would fire immediately after with
                    // pushed=files.size, even though the transfer was still
                    // in flight. Now we kick off the transfer and await its
                    // [TransferCompleted] / [TransferFailed] event on the
                    // bus before declaring the sync round complete.
                    val jobIdxResult = runCatching { transferService.sendFiles(peerId, files) }
                    val jobId = jobIdxResult.getOrNull()
                    if (jobId == null) {
                        Napier.w("sync push failed for share ${shareId.value}: ${jobIdxResult.exceptionOrNull()?.message}")
                    } else {
                        // Wait up to PUSH_AWAIT_TIMEOUT for the transfer to
                        // settle. We don't fail the sync on timeout - the
                        // transfer may legitimately be very large; we just
                        // log and let the next round catch up. A failure
                        // event aborts the sync round with that reason.
                        val terminal = withTimeoutOrNull(PUSH_AWAIT_TIMEOUT_MS) {
                            eventBus.events()
                                .filter { ev ->
                                    (ev is TransferCompleted && ev.jobId == jobId) ||
                                            (ev is TransferFailed && ev.jobId == jobId)
                                }
                                .first()
                        }
                        if (terminal is TransferFailed) {
                            throw DomainError.InvalidStateTransition("sync push transfer ${jobId.value} failed: ${terminal.reason}")
                        }
                        if (terminal == null) {
                            Napier.w(
                                "sync push transfer ${jobId.value} did not complete within " +
                                        "${PUSH_AWAIT_TIMEOUT_MS / 1000}s; continuing without blocking sync"
                            )
                        }
                    }
                }
            }

            // Deletions: just persist the tombstone advertisement; peer will
            // pick it up on the next sync round.
            val deletedPush = plan.toPush.filter { it.deleted }
            if (deletedPush.isNotEmpty()) {
                fileIndexRepository.upsertEntriesBatch(deletedPush)
            }
        }

        // Pull half: rely on peer's symmetric push. We *do* persist tombstones
        // they advertised so the local on-disk file will be removed by the
        // next indexer pass (and so we don't re-push them next round).
        if (canPull) {
            val deletedPull = plan.toPull.filter { it.deleted }
            if (deletedPull.isNotEmpty()) {
                fileIndexRepository.upsertEntriesBatch(deletedPull)
            }
        }

        // Step 6: finalize.
        eventBus.publish(
            SyncProgress(
                shareId,
                phase = "finalize",
                pushed = plan.toPush.size,
                pulled = plan.toPull.size,
                conflicts = plan.conflicts.size
            )
        )
        return plan
    }

    private fun mergeConflicts(
        existing: List<SyncConflict>,
        incoming: List<SyncConflict>
    ): List<SyncConflict> {
        // Dedupe by (shareId, path) - last-write-wins so the most recent
        // sync's view of the conflict replaces any stale entry.
        val byKey = (existing + incoming).associateBy { "${it.shareId.value}:${it.path}" }
        return byKey.values.toList()
    }

    companion object {
        /**
         * Max time we'll wait for a sync-push transfer to settle before
         * giving up and letting the next sync round catch up (issue #27).
         * 10 minutes is enough for a 1 GiB file at ~1.7 MB/s, well within
         * mobile-LAN typical throughput.
         */
        const val PUSH_AWAIT_TIMEOUT_MS: Long = 10L * 60L * 1000L
    }
}