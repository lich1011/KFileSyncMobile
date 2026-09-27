package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.BlocksRequestDto
import com.kfilesync.mobile.application.dto.BlocksResponseDto
import com.kfilesync.mobile.application.dto.RegisterRequestDto
import com.kfilesync.mobile.application.dto.RegisterResponseDto
import com.kfilesync.mobile.application.dto.ShareResultDto
import com.kfilesync.mobile.application.dto.TransferResultDto
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.IndexResponseDto
import com.kfilesync.mobile.application.dto.PairConfirmDto
import com.kfilesync.mobile.application.dto.PairRequestDto
import com.kfilesync.mobile.application.dto.PairResultDto
import com.kfilesync.mobile.application.dto.PairRevokeDto
import com.kfilesync.mobile.application.dto.ShareAuthorizeDto
import com.kfilesync.mobile.application.dto.ShareInviteDto
import com.kfilesync.mobile.application.dto.ShareLeaveDto
import com.kfilesync.mobile.application.dto.TransferAcceptDto
import com.kfilesync.mobile.application.dto.TransferCancelDto
import com.kfilesync.mobile.application.dto.TransferChunkAckDto
import com.kfilesync.mobile.application.dto.TransferRequestDto

/**
 * Outbound HTTP client used to talk to peer devices.
 *
 * Phase 1 (T1.4) wires fingerprint pinning on both platforms via the
 * platform-specific pinned engine factories:
 * - Android: `pinnedHttpClientEngine(deviceRepository)` installs a
 *   [com.kfilesync.mobile.platform.tls.PinningTrustManager] on the
 *   OkHttp engine.
 * - iOS: `pinnedHttpClientEngine(deviceRepository)` installs an
 *   [com.kfilesync.mobile.platform.tls.IosPinningChallengeHandler] on the
 *   Darwin (NSURLSession) engine.
 *
 * Both look at the leaf cert's SHA-256, compare against
 * `DeviceRepository.findPaired()`, and reject on mismatch.
 *
 * Phase 2 (T2.2 / T2.3) adds the transfer endpoints - request,
 * accept-response, per-chunk upload, cancel. The chunk upload uses a
 * separate per-request socket timeout because individual chunks can be up
 * to 16 MiB on Wi-Fi.
 *
 * Methods are `open` so unit tests can subclass with stubbed responses
 * (see `FakeLanSyncHttpClient` in commonTest). The engine factory is
 * supplied via constructor; production code injects the pinned engine.
 */
open class LanSyncHttpClient(
    engineFactory: HttpClientEngineFactory<*> = defaultEngine(),
    private val antiReplay: AntiReplaySigner? = null
) {
    private val client: HttpClient = buildClient(engineFactory)

    /**
     * Attach anti-replay headers (X-Device-Id / X-Timestamp / X-Nonce /
     * X-Fingerprint) to a mutating request when a signer is wired. GETs do
     * not call this - the server exempts read-only routes.
     */
    private fun HttpRequestBuilder.antiReplayHeaders() {
        antiReplay?.headers()?.forEach { (k, v) -> header(k, v) }
    }

    open suspend fun fetchInfo(baseUrl: String): DeviceInfoDto? = try {
        WireCodec.decodeDeviceInfo(client.get("$baseUrl${WireConstants.routeInfo}").body<ByteArray>())
    } catch (t: Throwable) {
        Napier.w("fetchInfo($baseUrl) failed:${t.message}")
        null
    }

    open suspend fun postPairRequest(baseUrl: String, body: PairRequestDto): Boolean = try {
        val resp = client.post("$baseUrl${WireConstants.routePairRequest}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(WireCodec.encodePairRequest(body))
        }
        resp.status == HttpStatusCode.Accepted || resp.status == HttpStatusCode.OK
    } catch (t: Throwable) {
        Napier.w("postPairRequest($baseUrl) failed:${t.message}")
        false
    }

    open suspend fun postPairConfirm(baseUrl: String, body: PairConfirmDto): PairResultDto = try {
        val resp = client.post("$baseUrl${WireConstants.routePairConfirm}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(WireCodec.encodePairConfirm(body))
        }
        WireCodec.decodePairResult(resp.body<ByteArray>())
    } catch (t: Throwable) {
        Napier.w("postPairConfirm($baseUrl) failed:${t.message}")
        PairResultDto(requestId = body.requestId, accepted = false, reason = t.message)
    }

    open suspend fun postPairRevoke(baseUrl: String, body: PairRevokeDto): Boolean = try {
        val resp = client.post("$baseUrl${WireConstants.routePairRevoke}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(WireCodec.encodePairRevoke(body))
        }
        resp.status == HttpStatusCode.OK
    } catch (t: Throwable) {
        Napier.w("postPairRevoke($baseUrl) failed:${t.message}")
        false
    }

    // ----- Phase 2: transfer endpoints -----

    open suspend fun postTransferRequest(baseUrl: String, body: TransferRequestDto): TransferAcceptDto = try {
        val resp = client.post("$baseUrl${WireConstants.routeTransferRequest}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(WireCodec.encodeTransferRequest(body))
        }
        WireCodec.decodeTransferAccept(resp.body<ByteArray>())
    } catch (t: Throwable) {
        Napier.w("postTransferRequest($baseUrl) failed:${t.message}")
        TransferAcceptDto(
            sessionId = body.sessionId,
            jobId = body.jobId,
            accepted = false,
            reason = t.message ?: "transport failure"
        )
    }

    open suspend fun postTransferChunk(
        baseUrl: String,
        jobId: String,
        fileId: String,
        chunkIndex: Int,
        chunkHash: String,
        bytes: ByteArray
    ): TransferChunkAckDto = try {
        val resp = client.post("$baseUrl${WireConstants.routeTransferChunkPrefix}$jobId/chunk/$fileId/$chunkIndex") {
            contentType(ContentType.Application.OctetStream)
            antiReplayHeaders()
            header(WireConstants.headerChunkHash, chunkHash)
            setBody(bytes)
        }
        WireCodec.decodeTransferChunkAck(resp.body<ByteArray>())
    } catch (t: Throwable) {
        Napier.w("postTransferChunk($baseUrl) failed:${t.message}")
        TransferChunkAckDto(
            jobId = jobId,
            fileId = fileId,
            chunkIndex = chunkIndex,
            verified = false
        )
    }

    open suspend fun postTransferCancel(baseUrl: String, body: TransferCancelDto): TransferResultDto = try {
        val resp = client.post("$baseUrl${WireConstants.routeTransferCancel}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(WireCodec.encodeTransferCancel(body))
        }
        resp.body<TransferResultDto>()
    } catch (t: Throwable) {
        Napier.w("postTransferCancel($baseUrl) failed:${t.message}")
        TransferResultDto(ok = false, error = t.message)
    }

    // ----- Phase 3: share endpoints -----

    /** Send a share invitation to the peer. Used by Phase 2-future mobile-creates-share flow. */
    open suspend fun postShareInvite(baseUrl: String, body: ShareInviteDto): ShareResultDto = try {
        val resp = client.post("$baseUrl${WireConstants.routeShareInvite}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(WireCodec.encodeShareInvite(body))
        }
        resp.body<ShareResultDto>()
    } catch (t: Throwable) {
        Napier.w("postShareInvite($baseUrl) failed:${t.message}")
        ShareResultDto(ok = false, error = t.message)
    }

    /** Confirm a peer's membership / permission for a share. Used by Phase 2-future cascade flow. */
    open suspend fun postShareAuthorize(baseUrl: String, body: ShareAuthorizeDto): ShareResultDto = try {
        val resp = client.post("$baseUrl${WireConstants.routeShareAuthorize}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(WireCodec.encodeShareAuthorize(body))
        }
        resp.body<ShareResultDto>()
    } catch (t: Throwable) {
        Napier.w("postShareAuthorize($baseUrl) failed:${t.message}")
        ShareResultDto(ok = false, error = t.message)
    }

    // ----- Phase 4: sync endpoints -----

    /**
     * Fetch the peer's view of a share's file index. The peer returns every
     * non-tombstoned entry plus tombstones updated in the last 30 days.
     * Returns null on transport failure so the caller can decide whether to
     * retry or abort the sync session.
     */
    open suspend fun getSyncIndex(baseUrl: String, shareId: String): IndexResponseDto? = try {
        val bytes = client.get("$baseUrl${WireConstants.routeSyncIndex}") {
            parameter("share_id", shareId)
        }.body<ByteArray>()
        WireCodec.decodeIndexResponse(bytes)
    } catch (t: Throwable) {
        Napier.w("getSyncIndex($baseUrl, $shareId) failed:${t.message}")
        null
    }

    // ----- Discovery registration (§7.3) -----

    /**
     * Announce ourselves to a peer's `POST /register`. Optional handshake
     * step used after manual-IP discovery so the peer can record our address
     * list proactively instead of waiting for the next mDNS pass. Neither
     * request nor response DTO has a core equivalent, so this stays on the
     * implicit ContentNegotiation JSON path - only the path comes from
     * [WireConstants].
     */
    open suspend fun postRegister(baseUrl: String, body: RegisterRequestDto): RegisterResponseDto = try {
        client.post("$baseUrl${WireConstants.routeRegister}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(body)
        }.body<RegisterResponseDto>()
    } catch (t: Throwable) {
        Napier.w("postRegister($baseUrl) failed:${t.message}")
        RegisterResponseDto(accepted = false, reason = t.message)
    }

    // ----- Share leave (notify creator) -----

    /**
     * Tell the share creator we left so they can drop our membership row.
     * Best-effort; the receiver also reconciles on the next sync round.
     */
    open suspend fun postShareLeave(baseUrl: String, body: ShareLeaveDto): ShareResultDto = try {
        val resp = client.post("$baseUrl${WireConstants.routeShareLeave}") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(WireCodec.encodeShareLeave(body))
        }
        resp.body<ShareResultDto>()
    } catch (t: Throwable) {
        Napier.w("postShareLeave($baseUrl) failed:${t.message}")
        ShareResultDto(ok = false, error = t.message)
    }

    // ----- Sync block fetch (content-addressed pull, §7.3) -----

    /**
     * Pull raw block contents for a set of paths from the peer's
     * `POST /sync/blocks`. Returns null on transport failure.
     */
    open suspend fun postSyncBlocks(baseUrl: String, body: BlocksRequestDto): BlocksResponseDto? = try {
        client.post("$baseUrl/api/lansync/v1/sync/blocks") {
            contentType(ContentType.Application.Json)
            antiReplayHeaders()
            setBody(body)
        }.body<BlocksResponseDto>()
    } catch (t: Throwable) {
        Napier.w("postSyncBlocks($baseUrl) failed:${t.message}")
        null
    }

    fun close() {
        client.close()
    }

    companion object {
        /** Bare platform engine (no pinning) - used by tests + diagnostics. */
        fun defaultEngine(): HttpClientEngineFactory<*> = httpClientEngine()

        private fun buildClient(engineFactory: HttpClientEngineFactory<*>): HttpClient =
            HttpClient(engineFactory) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    })
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    // Per-request 2 min ceiling - covers a 16 MiB chunk at ~150 KiB/s
                    // (poor-Wi-Fi worst case) with headroom. Sender retries on timeout.
                    requestTimeoutMillis = 120_000
                    socketTimeoutMillis = 60_000
                }
            }
    }
}

internal expect fun httpClientEngine(): HttpClientEngineFactory<*>