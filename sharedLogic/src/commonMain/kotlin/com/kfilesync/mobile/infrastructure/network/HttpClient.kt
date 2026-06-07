package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.IndexResponseDto
import com.kfilesync.mobile.application.dto.PairConfirmDto
import com.kfilesync.mobile.application.dto.PairRequestDto
import com.kfilesync.mobile.application.dto.PairResultDto
import com.kfilesync.mobile.application.dto.PairRevokeDto
import com.kfilesync.mobile.application.dto.ShareAuthorizeDto
import com.kfilesync.mobile.application.dto.ShareInviteDto
import com.kfilesync.mobile.application.dto.ShareResultDto
import com.kfilesync.mobile.application.dto.TransferAcceptDto
import com.kfilesync.mobile.application.dto.TransferCancelDto
import com.kfilesync.mobile.application.dto.TransferChunkAckDto
import com.kfilesync.mobile.application.dto.TransferChunkDto
import com.kfilesync.mobile.application.dto.TransferRequestDto
import com.kfilesync.mobile.application.dto.TransferResultDto
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Outbound HTTP client used to talk to peer devices.
 *
 * Phase 1 (T1.4) wires fingerprint pinning on both platforms via the
 * platform-specific pinned engine factories:
 * - Android: `pinnedHttpClientEngine(deviceRepository)` installs a
 * [com.kfilesync.mobile.platform.tls.PinningTrustManager] on the
 * OkHttp engine.
 * - iOS: `pinnedHttpClientEngine(deviceRepository)` installs an
 * [com.kfilesync.mobile.platform.tls.IosPinningChallengeHandler] on the
 * Darwin (URLSession) engine.
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
    engineFactory: HttpClientEngineFactory<*> = defaultEngine()
) {

    private val client: HttpClient = buildClient(engineFactory)

    open suspend fun fetchInfo(baseUrl: String): DeviceInfoDto? = try {
        client.get("$baseUrl/api/lansync/v1/info").body()
    } catch (t: Throwable) {
        Napier.w("fetchInfo($baseUrl) failed: ${t.message}")
        null
    }

    open suspend fun postPairRequest(baseUrl: String, body: PairRequestDto): Boolean = try {
        val resp = client.post("$baseUrl/api/lansync/v1/pair/request") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.status == HttpStatusCode.Accepted || resp.status == HttpStatusCode.OK
    } catch (t: Throwable) {
        Napier.w("postPairRequest($baseUrl) failed: ${t.message}")
        false
    }

    open suspend fun postPairConfirm(baseUrl: String, body: PairConfirmDto): PairResultDto = try {
        val resp = client.post("$baseUrl/api/lansync/v1/pair/confirm") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.body<PairResultDto>()
    } catch (t: Throwable) {
        Napier.w("postPairConfirm($baseUrl) failed: ${t.message}")
        PairResultDto(ok = false, error = t.message)
    }

    open suspend fun postPairRevoke(baseUrl: String, body: PairRevokeDto): PairResultDto = try {
        val resp = client.post("$baseUrl/api/lansync/v1/pair/revoke") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.body<PairResultDto>()
    } catch (t: Throwable) {
        Napier.w("postPairRevoke($baseUrl) failed: ${t.message}")
        PairResultDto(ok = false, error = t.message)
    }

    // ---- Phase 2: transfer endpoints ----

    open suspend fun postTransferRequest(baseUrl: String, body: TransferRequestDto): TransferAcceptDto = try {
        val resp = client.post("$baseUrl/api/lansync/v1/transfer/request") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.body<TransferAcceptDto>()
    } catch (t: Throwable) {
        Napier.w("postTransferRequest($baseUrl) failed: ${t.message}")
        TransferAcceptDto(
            sessionId = body.sessionId,
            jobId = body.jobId,
            accepted = false,
            reason = t.message ?: "transport failure"
        )
    }

    open suspend fun postTransferChunk(baseUrl: String, body: TransferChunkDto): TransferChunkAckDto = try {
        val resp = client.post("$baseUrl/api/lansync/v1/transfer/chunks") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.body<TransferChunkAckDto>()
    } catch (t: Throwable) {
        Napier.w("postTransferChunk($baseUrl) failed: ${t.message}")
        TransferChunkAckDto(
            ok = false,
            fileId = body.fileId,
            chunkIndex = body.chunkIndex,
            error = t.message ?: "transport failure"
        )
    }

    open suspend fun postTransferCancel(baseUrl: String, body: TransferCancelDto): TransferResultDto = try {
        val resp = client.post("$baseUrl/api/lansync/v1/transfer/cancel") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.body<TransferResultDto>()
    } catch (t: Throwable) {
        Napier.w("postTransferCancel($baseUrl) failed: ${t.message}")
        TransferResultDto(ok = false, error = t.message)
    }

    // ---- Phase 3: share endpoints ----

    /** Send a share invitation to the peer. Used by Phase 2-future mobile-creates-share flow. */
    open suspend fun postShareInvite(baseUrl: String, body: ShareInviteDto): ShareResultDto = try {
        val resp = client.post("$baseUrl/api/lansync/v1/share/invite") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.body<ShareResultDto>()
    } catch (t: Throwable) {
        Napier.w("postShareInvite($baseUrl) failed: ${t.message}")
        ShareResultDto(ok = false, error = t.message)
    }

    /** Confirm a peer's membership / permission for a share. Used by Phase 2-future cascade flow. */
    open suspend fun postShareAuthorize(baseUrl: String, body: ShareAuthorizeDto): ShareResultDto = try {
        val resp = client.post("$baseUrl/api/lansync/v1/share/authorize") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.body<ShareResultDto>()
    } catch (t: Throwable) {
        Napier.w("postShareAuthorize($baseUrl) failed: ${t.message}")
        ShareResultDto(ok = false, error = t.message)
    }

    // ---- Phase 4: sync endpoints ----

    /**
     * Fetch the peer's view of a share's file index. The peer returns every
     * non-tombstoned entry plus tombstones updated in the last 30 days.
     * Returns null on transport failure so the caller can decide whether to
     * retry or abort the sync session.
     */
    open suspend fun getSyncIndex(baseUrl: String, shareId: String): IndexResponseDto? = try {
        client.get("$baseUrl/api/lansync/v1/sync/index") {
            parameter("share_id", shareId)
        }.body<IndexResponseDto>()
    } catch (t: Throwable) {
        Napier.w("getSyncIndex($baseUrl, $shareId) failed: ${t.message}")
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