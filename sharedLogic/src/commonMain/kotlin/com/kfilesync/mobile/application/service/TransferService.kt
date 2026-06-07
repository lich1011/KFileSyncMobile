package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.application.dto.TransferAcceptDto
import com.kfilesync.mobile.application.dto.TransferCancelDto
import com.kfilesync.mobile.application.dto.TransferChunkAckDto
import com.kfilesync.mobile.application.dto.TransferChunkDto
import com.kfilesync.mobile.application.dto.TransferFileDto
import com.kfilesync.mobile.application.dto.TransferRequestDto
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.DomainError
import com.kfilesync.mobile.domain.event.ChunkVerificationFailed
import com.kfilesync.mobile.domain.event.TransferCompleted
import com.kfilesync.mobile.domain.event.TransferFailed
import com.kfilesync.mobile.domain.event.TransferProgressAdvanced
import com.kfilesync.mobile.domain.event.TransferRequested
import com.kfilesync.mobile.domain.model.Checkpoint
import com.kfilesync.mobile.domain.model.DeviceAddress
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.FileId
import com.kfilesync.mobile.domain.model.FilePlan
import com.kfilesync.mobile.domain.model.JobId
import com.kfilesync.mobile.domain.model.TransferDirection
import com.kfilesync.mobile.domain.model.TransferItem
import com.kfilesync.mobile.domain.model.TransferItemStatus
import com.kfilesync.mobile.domain.model.TransferJob
import com.kfilesync.mobile.domain.model.TransferProgress
import com.kfilesync.mobile.domain.model.TransferState
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.FileSink
import com.kfilesync.mobile.domain.port.FileSource
import com.kfilesync.mobile.domain.port.TransferRepository
import com.kfilesync.mobile.domain.port.PlatformFile
import com.kfilesync.mobile.domain.service.ChunkingStrategy
import com.kfilesync.mobile.infrastructure.crypto.Base64Codec
import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.SecureRng
import com.kfilesync.mobile.infrastructure.crypto.StreamingSha256
import com.kfilesync.mobile.infrastructure.crypto.blake3
import com.kfilesync.mobile.infrastructure.crypto.nextHex
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Snapshot of an in-flight or historical transfer for the UI layer.
 *
 * Decouples the UI from the heavier [TransferJob] aggregate root so the
 * ViewModel can collect a hot `StateFlow<List<TransferRow>>` without
 * holding chunk-hash lists in memory for completed jobs.
 */
data class TransferRow(
    val jobId: JobId,
    val direction: TransferDirection,
    val peerDeviceId: DeviceId,
    val peerAlias: String,
    val state: TransferState,
    val transferredBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int,
    val firstFileName: String,
    val updatedAt: Instant,
    val errorMessage: String? = null
) {
    val ratio: Float
        get() = if (totalBytes > 0L) (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        else if (totalFiles > 0) completedFiles.toFloat() / totalFiles.toFloat()
        else 0f
}

/**
 * Incoming-transfer request descriptor that surfaces to the receive-confirm UI.
 */
data class IncomingTransferRequest(
    val sessionId: String,
    val jobId: JobId,
    val fromDeviceId: DeviceId,
    val fromAlias: String,
    val totalBytes: Long,
    val fileNames: List<String>,
    val receivedAt: Instant = Clock.System.now()
)

/**
 * Driving port: outgoing & incoming transfer use cases (design doc §6.2.1).
 */
interface TransferAppService {
    /** Sender entry-point. Returns the JobId of the newly-created outgoing job. */
    suspend fun sendFiles(target: DeviceId, files: List<PlatformFile>): JobId

    /** User accepted an inbound request from the UI. */
    suspend fun acceptTransfer(sessionId: String, targetDirectory: String? = null): Result<Unit>

    /** User rejected an inbound request from the UI. */
    suspend fun rejectTransfer(sessionId: String): Result<Unit>

    suspend fun pauseTransfer(jobId: JobId): Result<Unit>

    suspend fun resumeTransfer(jobId: JobId): Result<Unit>

    suspend fun cancelTransfer(jobId: JobId): Result<Unit>

    /** Hot stream of every known transfer (most-recent first). */
    fun observeTransfers(): Flow<List<TransferRow>>

    /** Hot stream of pending inbound requests awaiting user confirmation. */
    fun observeIncomingRequests(): Flow<List<IncomingTransferRequest>>

    // -- Receiver-side wire callbacks (called by HTTP routes, not the UI) --

    /** `POST /transfer/request` body lands here. Returns the accept-DTO synchronously. */
    suspend fun onTransferRequest(body: TransferRequestDto): TransferAcceptDto

    /** `POST /transfer/chunks` body lands here. */
    suspend fun onTransferChunk(body: TransferChunkDto): TransferChunkAckDto

    /** `POST /transfer/cancel` body lands here. */
    suspend fun onTransferCancel(body: TransferCancelDto)

    /** Cancel all active transfers whose peer is [deviceId] (called on trust revocation). */
    suspend fun cancelTransfersForPeer(deviceId: DeviceId)

    /** Resume any jobs that were Active/Paused at process exit. */
    suspend fun resumeAfterRestart()
}

/**
 * Phase 2 implementation (T2.1 - T2.5).
 *
 * Sender pipeline:
 * 1. `sendFiles(peer, files)` plans each file (chunk size, BLAKE3 per chunk,
 * whole-file SHA-256) on `Dispatchers.Default`.
 * 2. Builds a [TransferJob] via `TransferJob.newOutgoing(...)`, persists it.
 * 3. POSTs `/transfer/request` to the peer; peer answers with
 * [TransferAcceptDto] (with a `skipChunks` resume map).
 * 4. For each file + each chunk (minus skip set), reads the chunk via the
 * [FileSource] adapter, BLAKE3s it, POSTs `/transfer/chunks` to the peer.
 * 5. On each ACK, updates the per-item checkpoint + emits a progress event.
 * 6. On the last ACK with `jobCompleted=true`, the job is marked completed.
 *
 * Receiver pipeline:
 * 1. `/transfer/request` -> store the incoming request + a Pending job;
 * surface to the user via [incomingRequests].
 * 2. User accepts in the UI -> [acceptTransfer] flips status to Active +
 * opens a temp file per item via [FileSink].
 * 3. `/transfer/chunks` -> verify BLAKE3 of the chunk, write into temp file,
 * advance checkpoint, return ACK with `fileCompleted` / `jobCompleted`.
 * 4. On file completion: streaming SHA-256 across the file's chunks (recomputed
 * from the temp file on disk) matches the manifest? Atomic move to final
 * destination via [FileSink.finalize].
 * 5. On job completion: publish [TransferCompleted].
 */
class TransferServiceImpl(
    private val transferRepository: TransferRepository,
    private val deviceRepository: DeviceRepository,
    private val eventBus: EventBus,
    private val httpClient: LanSyncHttpClient,
    private val localIdentityProvider: LocalIdentityProvider,
    private val fileSource: FileSource,
    private val fileSink: FileSink,
    private val chunkingStrategy: ChunkingStrategy,
    private val clock: () -> Instant = { Clock.System.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TransferAppService {

    private val transfers = MutableStateFlow<List<TransferRow>>(emptyList())
    private val incomingRequests = MutableStateFlow<List<IncomingTransferRequest>>(emptyList())

    /**
     * Map of jobId -> mutable session state. The sender's chunk loop reads/writes
     * here; the receiver reads here when chunks arrive out-of-order across files
     * (we keep a single streaming SHA-256 per file for the duration of its
     * receive). Receiver also stores the locator per fileId so chunk-write
     * doesn't have to re-open the sink.
     */
    private val sessions = mutableMapOf<JobId, ReceiveSession>()
    private val sessionsLock = Mutex()
    private val outgoingJobs = mutableMapOf<JobId, Job>()

    // ------------------ sender side ------------------

    override suspend fun sendFiles(target: DeviceId, files: List<PlatformFile>): JobId {
        require(files.isNotEmpty()) { "sendFiles requires at least one file" }
        val peer = deviceRepository.findById(target)
            ?: throw DomainError.DeviceNotFound(target)
        val peerState = peer.state
        if (peerState !is DeviceState.Paired) {
            throw DomainError.DeviceNotTrusted(target)
        }
        val now = clock()

        // 1. Plan every file (chunk hashes + whole-file sha256).
        val plans: List<FilePlan> = files.map { file ->
            val chunkSize = chunkingStrategy.computeChunkSize(file.sizeBytes)
            planFile(file, chunkSize)
        }

        val jobId = JobId(randomId("job"))
        val sessionId = randomId("sess")
        val job = TransferJob.newOutgoing(
            jobId = jobId,
            sessionId = sessionId,
            peer = target,
            files = plans,
            strategy = chunkingStrategy,
            createdAt = now
        )

        transferRepository.saveJob(job)
        publishRow(job, peer.alias)
        eventBus.publish(
            TransferRequested(
                jobId = jobId,
                peerDeviceId = target,
                totalBytes = job.totalBytes,
                totalFiles = job.totalFiles,
                occurredAt = now
            )
        )

        // 2. Spawn the upload coroutine - survives any UI rebind via [scope].
        val handle = scope.launch {
            runOutgoing(job, peer.addresses, peer.alias, files.associateBy { it.locator })
        }
        outgoingJobs[jobId] = handle

        return jobId
    }

    private suspend fun planFile(file: PlatformFile, chunkSize: Int): FilePlan = withContext(Dispatchers.Default) {
        val size = fileSource.size(file.locator).takeIf { it > 0 } ?: file.sizeBytes
        val sha = StreamingSha256()
        val chunkHashes = mutableListOf<String>()
        if (chunkSize == 0) {
            val bytes = fileSource.readWhole(file.locator)
            sha.update(bytes, 0, bytes.size)
            chunkHashes += HashProvider.blake3(bytes).toHexLower()
        } else {
            val totalChunks = ((size + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
            val buf = ByteArray(chunkSize)
            for (i in 0 until totalChunks) {
                val read = fileSource.readChunk(file.locator, i, chunkSize, buf)
                if (read <= 0) break
                sha.update(buf, 0, read)
                chunkHashes += HashProvider.blake3(buf, read).toHexLower()
            }
        }
        // issue #16: DON'T close here. The chunk-upload loop in
        // [uploadOneFile] re-reads the same locator; closing between plan
        // and upload would force the FileSource to re-open the underlying
        // SAF/security-scoped resource, opening a TOCTOU window where the
        // user could modify the file between hashing and sending. We keep
        // the source open for the full plan+send lifetime; [runOutgoing]
        // closes it once we've finished uploading.
        FilePlan(
            path = file.displayName,
            size = size,
            sha256 = sha.finalize().toHexLower(),
            chunkHashes = chunkHashes,
            fileId = FileId(randomId("f"))
        )
    }

    private suspend fun runOutgoing(
        job: TransferJob,
        addresses: List<DeviceAddress>,
        peerAlias: String,
        filesByLocator: Map<String, PlatformFile>
    ) {
        val baseUrl = addresses.firstOrNull()?.let { "https://${it.host}:${it.port}" }
        if (baseUrl == null) {
            failOutgoing(job.id, "peer has no reachable address")
            return
        }
        val local = localIdentityProvider.current()

        // 3. POST /transfer/request and await the accept reply.
        val acceptResp = httpClient.postTransferRequest(
            baseUrl = baseUrl,
            body = TransferRequestDto(
                sessionId = job.sessionId,
                jobId = job.id.value,
                fromDeviceId = local.deviceId.value,
                fromAlias = local.alias,
                shareId = job.shareId?.value,
                files = job.items.map {
                    TransferFileDto(
                        fileId = it.fileId.value,
                        path = it.path,
                        size = it.size,
                        sha256 = it.sha256,
                        chunkSize = it.manifest.chunkSize,
                        chunkHashes = it.manifest.chunkHashes
                    )
                }
            )
        )

        if (!acceptResp.accepted) {
            failOutgoing(job.id, acceptResp.reason ?: "peer rejected transfer")
            return
        }

        // 4. Move to Active.
        var current = transferRepository.findById(job.id) ?: job
        val started = current.start(clock()).getOrNull() ?: run {
            failOutgoing(job.id, "cannot start from ${current.state}")
            return
        }
        transferRepository.saveJob(started)
        current = started
        publishRow(current, peerAlias)

        // 5. Chunk loop. We resolve the source locator by path back to the
        //    original PlatformFile so we know which content URI to read.
        val locatorByPath = filesByLocator.mapKeys { (_, pf) -> pf.displayName }
        for (item in current.items) {
            val skipSet: Set<Int> = acceptResp.skipChunks[item.fileId.value]?.toSet() ?: emptySet()
            val locator = locatorByPath[item.path]?.locator
            if (locator == null) {
                failOutgoing(job.id, "missing source locator for ${item.path}")
                return
            }
            val ok = uploadOneFile(current, item, skipSet, baseUrl, locator, peerAlias)
            if (!ok) return
            // Re-read aggregate after every file to pick up updated checkpoints.
            current = transferRepository.findById(job.id) ?: current
        }

        // 6. Mark job completed.
        val completed = current.complete(clock()).getOrNull() ?: current
        transferRepository.saveJob(completed)
        publishRow(completed, peerAlias)
        eventBus.publish(TransferCompleted(jobId = job.id, totalBytes = completed.totalBytes))

        // Release every source we held open across plan+upload (issue #16).
        filesByLocator.keys.forEach { locator ->
            runCatching { fileSource.close(locator) }
        }
        outgoingJobs.remove(job.id)
        Napier.i("outgoing job ${job.id.value} completed: ${completed.totalBytes}B / ${completed.totalFiles}f")
    }

    private suspend fun uploadOneFile(
        job: TransferJob,
        item: TransferItem,
        skipSet: Set<Int>,
        baseUrl: String,
        locator: String,
        peerAlias: String
    ): Boolean {
        val chunkSize = item.manifest.chunkSize
        val total = item.manifest.totalChunks
        val buf = if (chunkSize == 0) ByteArray(item.size.toInt().coerceAtLeast(1)) else ByteArray(chunkSize)

        // Phase 6 (T6.2) hot-path optimisation: keep a single mutable shadow
        // of the aggregate's progress in memory and only re-query the DB at
        // file boundaries. The previous implementation called `findById`
        // after every chunk (a full join across `transfer_jobs` +
        // `transfer_items`); on a 1 GiB file with 8 MiB chunks that's 128
        // unnecessary multi-row queries per file. With this change we drop
        // to 1 query per file, and the per-chunk path is purely the chunk
        // POST + a single-row checkpoint UPDATE.
        //
        // We still publish the row + progress event on every chunk so the
        // UI keeps live progress - but we synthesise the row from the
        // shadow rather than rehydrating the aggregate.
        val shadow = job
        var transferredBytes = job.transferredBytes

        val perChunkBytes: Long = if (chunkSize == 0) item.size else chunkSize.toLong()
        // Account for already-acknowledged chunks (resume). Issue #15: the
        // last chunk may be shorter than `chunkSize`, so just multiplying
        // `skipSet.size * perChunkBytes` over-counts when the resume map
        // includes the tail chunk. Compute per-skipped-index exactly.
        if (skipSet.isNotEmpty()) {
            for (idx in skipSet) {
                transferredBytes += if (chunkSize == 0 || idx < total - 1) {
                    perChunkBytes
                } else {
                    val tail = item.size - (total - 1).toLong() * perChunkBytes
                    tail.coerceAtLeast(0L)
                }
            }
        }

        for (i in 0 until total) {
            if (i in skipSet) continue
            val read = if (chunkSize == 0) {
                val whole = fileSource.readWhole(locator)
                whole.copyInto(buf, 0, 0, whole.size)
                whole.size
            } else {
                fileSource.readChunk(locator, i, chunkSize, buf)
            }
            val effective = read.coerceAtLeast(0)
            // Avoid the per-chunk copyOfRange allocation on every iteration
            // - we only need a shrunk view on the *last* chunk where the
            // read can be smaller than the buffer.
            val chunkBytes = if (effective == buf.size) buf else buf.copyOfRange(0, effective)

            val ack = httpClient.postTransferChunk(
                baseUrl = baseUrl,
                body = TransferChunkDto(
                    sessionId = job.sessionId,
                    jobId = job.id.value,
                    fileId = item.fileId.value,
                    chunkIndex = i,
                    chunkSize = effective,
                    chunkHash = HashProvider.blake3(chunkBytes).toHexLower(),
                    dataB64 = Base64Codec.encode(chunkBytes)
                )
            )
            if (!ack.ok) {
                failOutgoing(job.id, ack.error ?: "peer rejected chunk")
                return false
            }
            // Hot-path: single-row checkpoint UPDATE only. No SELECT.
            val newChunksDone = (i + 1).coerceAtMost(total)
            transferRepository.updateItemCheckpoint(
                jobId = job.id,
                fileId = item.fileId,
                chunksDone = newChunksDone,
                status = if (ack.fileCompleted) "completed" else "active"
            )
            transferredBytes += effective.toLong()

            // Publish progress to UI from the in-memory shadow without
            // touching the DB. The shadow `TransferJob` is read-only here
            // (we only mutate the accumulated byte counters); we rebuild a
            // fresh [TransferRow] snapshot per chunk.
            val now = clock()
            val rowSnapshot = TransferRow(
                jobId = shadow.id,
                direction = shadow.direction,
                peerDeviceId = shadow.peerDeviceId,
                peerAlias = peerAlias,
                state = shadow.state,
                transferredBytes = transferredBytes,
                totalBytes = shadow.totalBytes,
                completedFiles = shadow.completedFiles + (if (ack.fileCompleted) 1 else 0),
                totalFiles = shadow.totalFiles,
                firstFileName = shadow.items.firstOrNull()?.path ?: "(no files)",
                updatedAt = now,
                errorMessage = shadow.errorMessage
            )
            publishRowSnapshot(rowSnapshot)
            eventBus.publish(
                TransferProgressAdvanced(
                    jobId = job.id,
                    transferredBytes = transferredBytes,
                    totalBytes = shadow.totalBytes,
                    completedFiles = shadow.completedFiles + (if (ack.fileCompleted) 1 else 0),
                    totalFiles = shadow.totalFiles
                )
            )
        }

        // File boundary: persist the shadow progress to the DB so a crash
        // mid-file doesn't lose the last-chunk-write delta.
        transferRepository.updateProgress(
            jobId = job.id,
            progress = TransferProgress(
                transferredBytes = transferredBytes,
                totalBytes = shadow.totalBytes,
                completedFiles = shadow.completedFiles + 1,
                totalFiles = shadow.totalFiles
            )
        )
        // Issue #16: do NOT close the source between files - close once at
        // the end of [runOutgoing]. Closing per-file would force re-open
        // for the next iteration of the same content-URI on Android.
        return true
    }

    /**
     * Phase 6 helper - push a pre-built [TransferRow] without rehydrating
     * from the DB. Used by the per-chunk progress path.
     *
     * Issue #26: uses `.update { }` so concurrent emitters (the outgoing
     * chunk-loop coroutine and the incoming chunk handler running on the
     * Ktor dispatcher) don't drop each other's updates via the classic
     * read-modify-write race on `value`.
     */
    private fun publishRowSnapshot(row: TransferRow) {
        transfers.update { current ->
            val without = current.filterNot { it.jobId == row.jobId }
            (listOf(row) + without).sortedByDescending { it.updatedAt }
        }
    }

    private suspend fun failOutgoing(jobId: JobId, reason: String) {
        val now = clock()
        val current = transferRepository.findById(jobId) ?: return
        val failed = current.fail(reason, now).getOrNull() ?: current
        transferRepository.saveJob(failed)
        publishRow(failed, peerAlias = lookupAlias(failed.peerDeviceId))
        eventBus.publish(TransferFailed(jobId = jobId, reason = reason))
        outgoingJobs.remove(jobId)
        Napier.w("outgoing job ${jobId.value} failed: $reason")
    }

    // ------------------ receiver side ------------------

    override suspend fun onTransferRequest(body: TransferRequestDto): TransferAcceptDto {
        val now = clock()
        val fromId = DeviceId(body.fromDeviceId)
        val from = deviceRepository.findById(fromId)
        if (from == null || from.state !is DeviceState.Paired) {
            Napier.w("transfer request from unknown / untrusted ${body.fromDeviceId}; rejecting")
            return TransferAcceptDto(
                sessionId = body.sessionId,
                jobId = body.jobId,
                accepted = false,
                reason = "not paired"
            )
        }

        // Build & persist the incoming job in Pending state. acceptTransfer() flips it Active.
        val plans = body.files.map {
            FilePlan(
                path = it.path,
                size = it.size,
                sha256 = it.sha256,
                chunkHashes = it.chunkHashes,
                fileId = FileId(it.fileId)
            )
        }
        val jobId = JobId(body.jobId)
        val existing = transferRepository.findById(jobId)
        val job = existing ?: TransferJob.newIncoming(
            jobId = jobId,
            sessionId = body.sessionId,
            peer = fromId,
            files = plans,
            strategy = chunkingStrategy,
            createdAt = now
        )
        transferRepository.saveJob(job)
        publishRow(job, from.alias)

        // Compute resume map from current per-item checkpoints (empty on first contact).
        val skipChunks: Map<String, List<Int>> = job.items.associate {
            it.fileId.value to (0 until it.checkpoint.chunksDone).toList()
        }.filterValues { it.isNotEmpty() }

        // Surface the request to the UI iff this is the first time we see it.
        if (existing == null) {
            val request = IncomingTransferRequest(
                sessionId = body.sessionId,
                jobId = jobId,
                fromDeviceId = fromId,
                fromAlias = body.fromAlias,
                totalBytes = job.totalBytes,
                fileNames = job.items.map { it.path }
            )
            // Issue #26: atomic update.
            incomingRequests.update { it + request }
        }

        eventBus.publish(
            TransferRequested(
                jobId = jobId,
                peerDeviceId = fromId,
                totalBytes = job.totalBytes,
                totalFiles = job.totalFiles
            )
        )

        // Note: we *accept* eagerly for resume (existing != null) so a re-request
        // after restart doesn't strand the peer. New requests are auto-accepted
        // here too - the receiver's accept-dialog gating happens in the UI by
        // pausing the job until the user taps. Phase 5 will tighten this to a
        // pause-by-default flow.
        return TransferAcceptDto(
            sessionId = body.sessionId,
            jobId = body.jobId,
            accepted = true,
            skipChunks = skipChunks
        )
    }

    override suspend fun acceptTransfer(sessionId: String, targetDirectory: String?): Result<Unit> {
        val pending = incomingRequests.value.firstOrNull { it.sessionId == sessionId }
            ?: return Result.failure(DomainError.PermissionDenied("no pending request for session $sessionId"))
        val job = transferRepository.findById(pending.jobId)
            ?: return Result.failure(DomainError.PermissionDenied("job ${pending.jobId.value} not found"))

        val session = sessionsLock.withLock {
            sessions.getOrPut(job.id) {
                ReceiveSession(jobId = job.id, targetDirectory = targetDirectory)
            }.also {
                it.targetDirectory = targetDirectory ?: it.targetDirectory
                it.userAccepted = true
            }
        }

        // Open temp sinks per file once; chunk-write reuses these locators.
        for (item in job.items) {
            if (session.tempLocators[item.fileId] == null) {
                val temp = fileSink.openTemp(item.path.substringAfterLast('/'))
                session.tempLocators[item.fileId] = temp
                transferRepository.saveJob(
                    job.withItem(item.fileId, item.copy(tempPath = temp, status = TransferItemStatus.Active), clock())
                )
            }
            if (session.sha256[item.fileId] == null) {
                session.sha256[item.fileId] = StreamingSha256()
            }
        }

        val started = job.start(clock()).getOrNull() ?: job
        transferRepository.saveJob(started)
        publishRow(started, peerAlias = lookupAlias(started.peerDeviceId))
        // Remove from pending list.
        incomingRequests.update { it.filter { req -> req.sessionId != sessionId } }
        return Result.success(Unit)
    }

    override suspend fun rejectTransfer(sessionId: String): Result<Unit> {
        val pending = incomingRequests.value.firstOrNull { it.sessionId == sessionId }
            ?: return Result.failure(DomainError.PermissionDenied("no pending request"))
        val job = transferRepository.findById(pending.jobId) ?: return Result.success(Unit)
        val failed = job.fail("rejected by user", clock()).getOrNull() ?: job
        transferRepository.saveJob(failed)
        incomingRequests.update { it.filter { req -> req.sessionId != sessionId } }
        publishRow(failed, peerAlias = lookupAlias(failed.peerDeviceId))
        eventBus.publish(TransferFailed(jobId = pending.jobId, reason = "rejected by user"))
        return Result.success(Unit)
    }

    override suspend fun onTransferChunk(body: TransferChunkDto): TransferChunkAckDto {
        val jobId = JobId(body.jobId)
        val fileId = FileId(body.fileId)
        val job = transferRepository.findById(jobId) ?: return TransferChunkAckDto(
            ok = false, fileId = body.fileId, chunkIndex = body.chunkIndex, error = "unknown job"
        )
        val item = job.items.firstOrNull { it.fileId == fileId } ?: return TransferChunkAckDto(
            ok = false, fileId = body.fileId, chunkIndex = body.chunkIndex, error = "unknown file"
        )

        val session = sessionsLock.withLock {
            sessions.getOrPut(jobId) { ReceiveSession(jobId = jobId, targetDirectory = null) }
        }

        // Issue #14: gate all chunk handling on the user having explicitly
        // tapped Accept in the UI. Until then we refuse with a non-fatal 425
        // -ish reason so the sender can retry once the user is ready.
        if (!session.userAccepted) {
            // Resume case: if all items already have a tempPath persisted,
            // we infer that the user had previously accepted and we're just
            // resuming after a restart. Flip the flag and proceed.
            val allHaveTemp = job.items.all { it.tempPath != null }
            if (allHaveTemp && job.items.isNotEmpty()) {
                session.userAccepted = true
            } else {
                return TransferChunkAckDto(
                    ok = false,
                    fileId = body.fileId,
                    chunkIndex = body.chunkIndex,
                    error = "awaiting user acceptance"
                )
            }
        }

        // Auto-open temp sink if accept-dialog wasn't routed (e.g. resume-on-restart).
        val tempLocator = session.tempLocators.getOrPut(fileId) {
            item.tempPath ?: fileSink.openTemp(item.path.substringAfterLast('/'))
        }
        val sha = session.sha256.getOrPut(fileId) { StreamingSha256() }

        // 1. Verify chunk-level BLAKE3.
        val decoded: ByteArray = try { Base64Codec.decode(body.dataB64) } catch (t: Throwable) {
            return TransferChunkAckDto(
                ok = false, fileId = body.fileId, chunkIndex = body.chunkIndex,
                error = "bad base64: ${t.message}"
            )
        }

        val expectedHash = item.manifest.chunkHashes.getOrNull(body.chunkIndex)
        val actualHash = HashProvider.blake3(decoded).toHexLower()
        if (expectedHash == null || actualHash != expectedHash) {
            eventBus.publish(ChunkVerificationFailed(jobId, fileId, body.chunkIndex))
            return TransferChunkAckDto(
                ok = false, fileId = body.fileId, chunkIndex = body.chunkIndex,
                error = "chunk hash mismatch (expected=$expectedHash actual=$actualHash)"
            )
        }

        // 2. Write chunk into temp file at the right offset.
        val offset = body.chunkIndex.toLong() *
                (if (item.manifest.chunkSize == 0) 0L else item.manifest.chunkSize.toLong())
        fileSink.writeChunk(tempLocator, offset, decoded, decoded.size)
        sha.update(decoded, 0, decoded.size)

        // 3. Advance checkpoint + emit progress.
        val newChunksDone = (body.chunkIndex + 1).coerceAtMost(item.manifest.totalChunks)
        transferRepository.updateItemCheckpoint(
            jobId = jobId, fileId = fileId, chunksDone = newChunksDone,
            status = if (newChunksDone >= item.manifest.totalChunks) "verifying" else "active"
        )
        var refreshed = transferRepository.findById(jobId) ?: job
        transferRepository.updateProgress(jobId, refreshed.progress())

        // 4. File completed? Verify whole-file SHA-256 + atomic move.
        var fileCompleted = false
        var jobCompleted = false
        if (newChunksDone >= item.manifest.totalChunks) {
            val finalSha = sha.finalize().toHexLower()
            if (finalSha != item.sha256) {
                fileSink.discard(tempLocator)
                session.tempLocators.remove(fileId)
                session.sha256.remove(fileId)
                refreshed = refreshed.fail("SHA-256 mismatch on ${item.path}", clock()).getOrNull() ?: refreshed
                transferRepository.saveJob(refreshed)
                publishRow(refreshed, peerAlias = lookupAlias(refreshed.peerDeviceId))
                eventBus.publish(TransferFailed(jobId, "SHA-256 mismatch on ${item.path}"))
                return TransferChunkAckDto(
                    ok = false, fileId = body.fileId, chunkIndex = body.chunkIndex,
                    error = "sha256 mismatch"
                )
            }

            val finalLocator = fileSink.finalize(
                tempLocator = tempLocator,
                targetDirectory = session.targetDirectory,
                fileName = item.path.substringAfterLast('/')
            )

            session.tempLocators.remove(fileId)
            session.sha256.remove(fileId)
            refreshed = refreshed.withItem(
                fileId,
                item.markCompleted().copy(tempPath = finalLocator),
                clock()
            )
            transferRepository.saveJob(refreshed)
            fileCompleted = true
        }

        // 5. Job completed?
        if (refreshed.items.all { it.isComplete }) {
            refreshed = refreshed.complete(clock()).getOrNull() ?: refreshed
            transferRepository.saveJob(refreshed)
            sessionsLock.withLock { sessions.remove(jobId) }
            jobCompleted = true
            eventBus.publish(TransferCompleted(jobId, refreshed.totalBytes))
        }
        publishRow(refreshed, peerAlias = lookupAlias(refreshed.peerDeviceId))
        eventBus.publish(
            TransferProgressAdvanced(
                jobId = jobId,
                transferredBytes = refreshed.transferredBytes,
                totalBytes = refreshed.totalBytes,
                completedFiles = refreshed.completedFiles,
                totalFiles = refreshed.totalFiles
            )
        )

        return TransferChunkAckDto(
            ok = true,
            fileId = body.fileId,
            chunkIndex = body.chunkIndex,
            fileCompleted = fileCompleted,
            jobCompleted = jobCompleted
        )
    }

    override suspend fun onTransferCancel(body: TransferCancelDto) {
        val jobId = JobId(body.jobId)
        val job = transferRepository.findById(jobId) ?: return
        val cancelled = job.cancel(clock()).getOrNull() ?: job
        transferRepository.saveJob(cancelled)
        cleanupSession(jobId)
        publishRow(cancelled, peerAlias = lookupAlias(cancelled.peerDeviceId))
    }

    // ------------------ common control ------------------

    override suspend fun pauseTransfer(jobId: JobId): Result<Unit> {
        val job = transferRepository.findById(jobId)
            ?: return Result.failure(DomainError.PermissionDenied("unknown job"))
        val paused = job.pause(clock()).getOrElse { return Result.failure(it as Throwable) }
        transferRepository.saveJob(paused)
        outgoingJobs.remove(jobId)?.cancel()
        publishRow(paused, peerAlias = lookupAlias(paused.peerDeviceId))
        return Result.success(Unit)
    }

    override suspend fun resumeTransfer(jobId: JobId): Result<Unit> {
        val job = transferRepository.findById(jobId)
            ?: return Result.failure(DomainError.PermissionDenied("unknown job"))
        // Outgoing: re-spawn the upload coroutine. Incoming: nothing to do -
        // the peer drives the chunks.
        if (job.direction != TransferDirection.Outgoing) return Result.success(Unit)
        val peer = deviceRepository.findById(job.peerDeviceId)
            ?: return Result.failure(DomainError.DeviceNotFound(job.peerDeviceId))
        // Source locators can't be reconstructed across restarts (Android SAF
        // URIs aren't persistent without takePersistableUriPermission). For
        // Phase 2 we surface this to the UI as "needs re-pick"; resume mostly
        // exists for the receive side.
        return Result.failure<Unit>(
            DomainError.PermissionDenied("re-pick the file to resume an outgoing transfer")
        ).also { Napier.w("resumeTransfer($jobId): outgoing resume requires re-pick; peer=${peer.alias}") }
    }

    override suspend fun cancelTransfer(jobId: JobId): Result<Unit> {
        val job = transferRepository.findById(jobId)
            ?: return Result.failure(DomainError.PermissionDenied("unknown job"))
        val cancelled = job.cancel(clock()).getOrElse { return Result.failure(it as Throwable) }
        transferRepository.saveJob(cancelled)
        outgoingJobs.remove(jobId)?.cancel()
        cleanupSession(jobId)

        // Best-effort: tell the peer.
        if (job.direction == TransferDirection.Outgoing) {
            val peer = deviceRepository.findById(job.peerDeviceId)
            peer?.addresses?.firstOrNull()?.let { addr ->
                httpClient.postTransferCancel(
                    baseUrl = "https://${addr.host}:${addr.port}",
                    body = TransferCancelDto(
                        sessionId = job.sessionId,
                        jobId = jobId.value,
                        reason = "user cancelled"
                    )
                )
            }
        }

        publishRow(cancelled, peerAlias = lookupAlias(cancelled.peerDeviceId))
        return Result.success(Unit)
    }

    override suspend fun resumeAfterRestart() {
        val pending = transferRepository.findIncompleteJobs()
        Napier.i("resumeAfterRestart: ${pending.size} jobs to inspect")
        // For the receive side we just rehydrate transfers list; the peer
        // will eventually re-POST /transfer/request and our checkpoints
        // make the resume map non-empty.
        val rows = transferRepository.findAll().map { rowFor(it, lookupAlias(it.peerDeviceId)) }
        transfers.value = rows
    }

    override fun observeTransfers(): Flow<List<TransferRow>> = transfers.asStateFlow()

    override fun observeIncomingRequests(): Flow<List<IncomingTransferRequest>> = incomingRequests.asStateFlow()

    // ------------------ helpers ------------------

    private fun cleanupSession(jobId: JobId) {
        scope.launch {
            sessionsLock.withLock {
                sessions.remove(jobId)?.tempLocators?.values?.forEach { temp ->
                    runCatching { fileSink.discard(temp) }
                }
            }
        }
    }

    private suspend fun publishRow(job: TransferJob, peerAlias: String) {
        val row = rowFor(job, peerAlias)
        // Issue #26: atomic update so concurrent emitters don't race.
        transfers.update { current ->
            val without = current.filterNot { it.jobId == job.id }
            (listOf(row) + without).sortedByDescending { it.updatedAt }
        }
    }

    private fun rowFor(job: TransferJob, peerAlias: String): TransferRow = TransferRow(
        jobId = job.id,
        direction = job.direction,
        peerDeviceId = job.peerDeviceId,
        peerAlias = peerAlias,
        state = job.state,
        transferredBytes = job.transferredBytes,
        totalBytes = job.totalBytes,
        completedFiles = job.completedFiles,
        totalFiles = job.totalFiles,
        firstFileName = job.items.firstOrNull()?.path ?: "(no files)",
        updatedAt = job.updatedAt,
        errorMessage = job.errorMessage
    )

    override suspend fun cancelTransfersForPeer(deviceId: DeviceId) {
        val incomplete = transferRepository.findIncompleteJobs()
            .filter { it.peerDeviceId == deviceId }
        for (job in incomplete) {
            runCatching { cancelTransfer(job.id) }
                .onFailure { Napier.w("cancelTransfersForPeer: cancel(${job.id.value}) failed: ${it.message}") }
        }
        if (incomplete.isNotEmpty()) {
            Napier.i("cancelTransfersForPeer: cancelled ${incomplete.size} jobs for ${deviceId.value}")
        }
    }

    private suspend fun lookupAlias(id: DeviceId): String =
        deviceRepository.findById(id)?.alias ?: id.value.take(8)

    private fun randomId(prefix: String): String {
        // Issue #47/#48: use the OS CSPRNG rather than kotlin.random.Random.
        // JobIds / sessionIds / fileIds are used as path prefixes and DB
        // keys; collisions here aren't catastrophic but predictability is.
        // Six random bytes (48 bits) gives 2^24 birthday-collision strength,
        // ample for the per-process job ids while keeping log lines tidy.
        return "$prefix-${SecureRng.nextHex(6)}"
    }

    /**
     * Per-job receive-side scratch state. Holds the per-file temp locator and
     * the streaming SHA-256 hasher across chunk arrivals.
     *
     * Kept off the [TransferJob] aggregate so the aggregate stays pure data;
     * domain code doesn't need to know about live hashers.
     *
     * `userAccepted` is the gate for issue #14: until the user taps Accept
     * in the UI ([acceptTransfer] flips it to true), [onTransferChunk]
     * refuses to allocate temp files or write data. The wire-level
     * `/transfer/request` still responds `accepted=true` (otherwise the
     * sender would drop the session); chunks are just held off.
     */
    private class ReceiveSession(
        @Suppress("unused") val jobId: JobId,
        var targetDirectory: String?,
        var userAccepted: Boolean = false
    ) {
        val tempLocators: MutableMap<FileId, String> = mutableMapOf()
        val sha256: MutableMap<FileId, StreamingSha256> = mutableMapOf()
    }
}