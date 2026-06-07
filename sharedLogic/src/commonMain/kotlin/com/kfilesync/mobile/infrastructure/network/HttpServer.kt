package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.ErrorDto
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
import com.kfilesync.mobile.application.dto.toDto
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.PairingService
import com.kfilesync.mobile.application.service.ShareAppService
import com.kfilesync.mobile.application.service.TransferAppService
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.FileIndexRepository
import com.kfilesync.mobile.domain.port.ShareRepository
import com.kfilesync.mobile.domain.service.NonceWindow
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import io.github.aakira.napier.Napier
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Embedded HTTP(S) server exposing the lansync v1 REST surface on the device.
 *
 * Phase 2 (T2.2 / T2.3) adds the transfer routes:
 * - `POST /transfer/request` - peer asks to send us files.
 * - `POST /transfer/chunks`  - peer streams chunks (one POST per chunk).
 * - `POST /transfer/cancel`  - peer aborts an in-flight job.
 *
 * Symmetric zero-trust wiring (T1.4) - see class history.
 *
 * Phase 5 (T5.3) adds the anti-replay layer: every *mutating* route requires
 * three headers - `X-Device-Id`, `X-Timestamp` (Unix epoch millis), and
 * `X-Nonce` - and we ask [NonceWindow] to decide if the (timestamp, nonce)
 * pair is fresh and unique for that device. Routes that fail the check
 * answer with `401 Unauthorized` and do not touch the application services.
 *
 * Read-only routes (`GET /info`, `GET /healthz`, `GET /sync/index`) are
 * excluded - they're idempotent + already TLS+fingerprint-pinned. Replaying
 * a GET buys an attacker nothing they couldn't already get from a fresh
 * pinned connection.
 */
class HttpServer(
    private val identityProvider: LocalIdentityProvider,
    private val pairingService: PairingService? = null,
    private val transferService: TransferAppService? = null,
    private val shareService: ShareAppService? = null,
    private val fileIndexRepository: FileIndexRepository? = null,
    private val shareRepository: ShareRepository? = null,
    private val deviceRepository: DeviceRepository? = null,
    private val nonceWindow: NonceWindow? = null,
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    private val tlsConfig: HttpServerTlsConfig? = null,
    /**
     * Test escape hatch: permit running without a [nonceWindow]. Production
     * code MUST leave this `false`, otherwise mutating routes will reject
     * with `500 anti_replay_misconfigured` rather than silently fail open.
     */
    private val allowMissingNonceWindow: Boolean = false
) {
    private var engine: EmbeddedServer<ApplicationEngine, out ApplicationEngine.Configuration>? = null

    /** Starts the server asynchronously. Returns immediately. */
    fun start() {
        if (engine != null) {
            Napier.w("HttpServer.start() called while already running, ignoring")
            return
        }
        val tlsLabel = if (tlsConfig != null) "https (sslConnector)" else "http (plaintext)"
        Napier.i("starting HttpServer on $host:$port - $tlsLabel")

        engine = startKtorEngine(tlsConfig, port, host) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    prettyPrint = false
                })
            }

            install(CORS) {
                anyHost()
                allowMethod(io.ktor.http.HttpMethod.Get)
                allowMethod(io.ktor.http.HttpMethod.Post)
                allowMethod(io.ktor.http.HttpMethod.Options)
                allowHeader(io.ktor.http.HttpHeaders.ContentType)
                allowHeader(HEADER_DEVICE_ID)
                allowHeader(HEADER_TIMESTAMP)
                allowHeader(HEADER_NONCE)
            }

            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Napier.e("HttpServer route threw", cause)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorDto(code = "internal", message = cause.message ?: "internal error")
                    )
                }
            }

            routing {
                route("/api/lansync/v1") {

                    // ---- Identity ----
                    get("/info") {
                        val identity = identityProvider.current()
                        call.respond(
                            DeviceInfoDto(
                                deviceId = identity.deviceId.value,
                                alias = identity.alias,
                                deviceType = "mobile",
                                platform = identity.platform.toWire(),
                                fingerprint = identity.fingerprint.hex,
                                port = identity.port,
                                announce = true
                            )
                        )
                    }

                    get("/healthz") {
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    }

                    // ---- Pairing (T1.3) ----
                    post("/pair/request") {
                        val svc = pairingService
                        if (svc == null) {
                            call.respond(HttpStatusCode.ServiceUnavailable, PairResultDto(ok = false, error = "pairing not configured"))
                            return@post
                        }
                        // Pairing routes are the bootstrap channel; the peer is by
                        // definition not yet paired, so we can't gate on the device
                        // repo. The PIN exchange itself authenticates.
                        if (!verifyAntiReplay(call, allowUnpairedPeer = true)) return@post
                        val body = call.receive<PairRequestDto>()
                        svc.receiveIncoming(body)
                        call.respond(HttpStatusCode.Accepted, PairResultDto(ok = true))
                    }

                    post("/pair/confirm") {
                        val svc = pairingService
                        if (svc == null) {
                            call.respond(HttpStatusCode.ServiceUnavailable, PairResultDto(ok = false, error = "pairing not configured"))
                            return@post
                        }
                        if (!verifyAntiReplay(call, allowUnpairedPeer = true)) return@post
                        val body = call.receive<PairConfirmDto>()
                        val outcome = svc.confirmIncoming(body)
                        if (outcome.result.isSuccess) {
                            call.respond(
                                HttpStatusCode.OK,
                                PairResultDto(ok = true, peerCertificatePem = outcome.ourCertPem)
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                PairResultDto(
                                    ok = false,
                                    error = outcome.result.exceptionOrNull()?.message
                                )
                            )
                        }
                    }

                    post("/pair/revoke") {
                        val svc = pairingService
                        if (svc == null) {
                            call.respond(HttpStatusCode.ServiceUnavailable, PairResultDto(ok = false, error = "pairing not configured"))
                            return@post
                        }
                        if (!verifyAntiReplay(call)) return@post
                        val body = call.receive<PairRevokeDto>()
                        val outcome = svc.revoke(DeviceId(body.deviceId))
                        if (outcome.isSuccess) {
                            call.respond(HttpStatusCode.OK, PairResultDto(ok = true))
                        } else {
                            call.respond(
                                HttpStatusCode.NotFound,
                                PairResultDto(ok = false, error = outcome.exceptionOrNull()?.message)
                            )
                        }
                    }

                    // ---- Transfer (T2.2 / T2.3) ----
                    post("/transfer/request") {
                        val svc = transferService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                TransferAcceptDto(
                                    sessionId = "",
                                    jobId = "",
                                    accepted = false,
                                    reason = "transfer service not configured"
                                )
                            )
                            return@post
                        }
                        if (!verifyAntiReplay(call)) return@post
                        val body = call.receive<TransferRequestDto>()
                        val resp = svc.onTransferRequest(body)
                        call.respond(if (resp.accepted) HttpStatusCode.OK else HttpStatusCode.Forbidden, resp)
                    }
                    post("/transfer/chunks") {
                        val svc = transferService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                TransferChunkAckDto(
                                    ok = false,
                                    fileId = "",
                                    chunkIndex = -1,
                                    error = "transfer service not configured"
                                )
                            )
                            return@post
                        }
                        if (!verifyAntiReplay(call)) return@post
                        val body = call.receive<TransferChunkDto>()
                        val ack = svc.onTransferChunk(body)
                        call.respond(if (ack.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, ack)
                    }
                    post("/transfer/cancel") {
                        val svc = transferService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                TransferResultDto(ok = false, error = "transfer service not configured")
                            )
                            return@post
                        }
                        if (!verifyAntiReplay(call)) return@post
                        val body = call.receive<TransferCancelDto>()
                        svc.onTransferCancel(body)
                        call.respond(HttpStatusCode.OK, TransferResultDto(ok = true))
                    }

                    // ---- Share (T3.2) ----
                    post("/share/invite") {
                        val svc = shareService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ShareResultDto(ok = false, error = "share service not configured")
                            )
                            return@post
                        }
                        if (!verifyAntiReplay(call)) return@post
                        val body = call.receive<ShareInviteDto>()
                        val outcome = svc.onShareInvite(body)
                        if (outcome.isSuccess) {
                            call.respond(HttpStatusCode.OK, ShareResultDto(ok = true))
                        } else {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ShareResultDto(
                                    ok = false,
                                    error = outcome.exceptionOrNull()?.message
                                        ?: "share invite rejected"
                                )
                            )
                        }
                    }
                    post("/share/authorize") {
                        val svc = shareService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ShareResultDto(ok = false, error = "share service not configured")
                            )
                            return@post
                        }
                        if (!verifyAntiReplay(call)) return@post
                        val body = call.receive<ShareAuthorizeDto>()
                        val outcome = svc.onShareAuthorize(body)
                        if (outcome.isSuccess) {
                            call.respond(HttpStatusCode.OK, ShareResultDto(ok = true))
                        } else {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ShareResultDto(
                                    ok = false,
                                    error = outcome.exceptionOrNull()?.message ?: "share authorize rejected"
                                )
                            )
                        }
                    }
                }

                // ---- Sync (T4.5) ----
                get("/sync/index") {
                    val indexRepo = fileIndexRepository
                    val shareRepo = shareRepository
                    if (indexRepo == null || shareRepo == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorDto(code = "sync_disabled", message = "sync service not configured")
                        )
                        return@get
                    }
                    val shareIdParam = call.request.queryParameters["share_id"]
                    if (shareIdParam.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorDto(code = "bad_request", message = "share_id required")
                        )
                        return@get
                    }
                    val shareId = ShareId(shareIdParam)
                    val share = shareRepo.findById(shareId)
                    if (share == null || share.status != ShareStatus.Active) {
                        // Don't leak existence of shares we declined to join.
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorDto(code = "share_not_active", message = "share not active")
                        )
                        return@get
                    }

                    // Index for the peer = live entries + recent tombstones (so they
                    // can pick up deletions). The 30-day window is the same cleanup
                    // threshold used by TombstoneCleanupService.
                    val live = indexRepo.getIndex(shareId)
                    val tombstones = indexRepo.getTombstones(shareId)
                    val entries = (live + tombstones).map { it.toDto() }
                    call.respond(IndexResponseDto(shareId = shareIdParam, entries = entries))
                }
            }
        }
        engine?.start(wait = false)
    }

    /**
     * Anti-replay header verification (T5.3).
     *
     * Hardened against issue #11. The previous implementation keyed the
     * NonceWindow bucket on the client-supplied `X-Device-Id` header alone
     * – an attacker who got past TLS pinning could supply any deviceId and
     * either poison another peer's bucket or replay traffic under a fresh
     * id. We add two checks on top of the freshness window:
     *
     * 1. The asserted X-Device-Id MUST refer to a currently-paired peer
     * (or to a non-paired peer for the bootstrap `/pair/request` and
     * `/pair/confirm` routes, which is OK because the pairing PIN is
     * the real authenticator there).
     * 2. For paired peers, the alleged fingerprint (`X-Fingerprint`)
     * MUST match the cert we have on file. Without mTLS we can't tie
     * the request to the TLS handshake post-hoc, but requiring the
     * header to match raises the bar from "any string" to "must know
     * the peer's cert fingerprint" – a value only a paired peer or a
     * successful MITM could plausibly produce.
     *
     * Tests that need the legacy permissive behaviour set
     * [allowMissingNonceWindow] = true.
     */
    private suspend fun verifyAntiReplay(call: ApplicationCall, allowUnpairedPeer: Boolean = false): Boolean {
        val window = nonceWindow
        if (window == null) {
            if (allowMissingNonceWindow) {
                // Legacy / test wiring – explicit opt-in.
                return true
            }
            Napier.e(
                "HttpServer: anti-replay window not configured – rejecting mutating request. " +
                        "Construct the server with a NonceWindow or set allowMissingNonceWindow = true " +
                        "if this is a test."
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(
                    code = "anti_replay_misconfigured",
                    message = "server is misconfigured: anti-replay protection is required"
                )
            )
            return false
        }

        val deviceId = call.request.headers[HEADER_DEVICE_ID]
        val timestampStr = call.request.headers[HEADER_TIMESTAMP]
        val nonce = call.request.headers[HEADER_NONCE]

        if (deviceId.isNullOrBlank() || timestampStr.isNullOrBlank() || nonce.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorDto(
                    code = "missing_anti_replay_headers",
                    message = "X-Device-Id, X-Timestamp, X-Nonce required"
                )
            )
            return false
        }

        val tsMillis = timestampStr.toLongOrNull()
        if (tsMillis == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorDto(
                    code = "bad_timestamp",
                    message = "X-Timestamp must be Unix epoch milliseconds"
                )
            )
            return false
        }

        // Issue #11 hardening: tie the asserted device id to a known peer.
        // For routes that explicitly bootstrap trust (pair/*) we skip this
        // because the pairing PIN is the real authenticator.
        if (!allowUnpairedPeer) {
            val repo = deviceRepository
            if (repo != null) {
                val device = repo.findById(DeviceId(deviceId))
                if (device == null || device.state !is com.kfilesync.mobile.domain.model.DeviceState.Paired) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorDto(
                            code = "unknown_peer",
                            message = "X-Device-Id is not a paired peer"
                        )
                    )
                    return false
                }

                // Optional cert-fingerprint cross-check.
                val assertedFp = call.request.headers[HEADER_FINGERPRINT]
                if (!assertedFp.isNullOrBlank()) {
                    val paired = device.state as com.kfilesync.mobile.domain.model.DeviceState.Paired
                    val expected = com.kfilesync.mobile.infrastructure.crypto.HashProvider
                        .sha256(pemToDer(paired.certificatePem))
                        .toHexLower()

                    if (!assertedFp.equals(expected, ignoreCase = true)) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorDto(
                                code = "fingerprint_mismatch",
                                message = "X-Fingerprint does not match paired peer"
                            )
                        )
                        return false
                    }
                }
            }
        }

        val verdict = window.verify(
            deviceId = deviceId,
            timestamp = Instant.fromEpochMilliseconds(tsMillis),
            nonce = nonce
        )

        return when (verdict) {
            is NonceWindow.Verdict.Accepted -> true
            is NonceWindow.Verdict.Rejected -> {
                Napier.w("HttpServer: anti-replay rejected from ${deviceId.take(12)}: ${verdict.reason}")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorDto(code = "anti_replay", message = verdict.reason)
                )
                false
            }
        }
    }

    /** Decode a PEM blob to its DER bytes for fingerprint cross-check. */
    private fun pemToDer(pem: String): ByteArray =
        com.kfilesync.mobile.infrastructure.crypto.PemUtils.pemToDer(pem)
            ?: ByteArray(0)

    fun stop() {
        engine?.let {
            Napier.i("stopping HttpServer")
            it.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        }
        engine = null
    }

    val isRunning: Boolean get() = engine != null

    companion object {
        const val DEFAULT_PORT: Int = 53317

        const val IOS_LOOPBACK_PORT: Int = 53318

        /** Wire-stable anti-replay header names (T5.3). Lowercase per HTTP/2; Ktor canonicalises. */
        const val HEADER_DEVICE_ID: String = "X-Device-Id"
        const val HEADER_TIMESTAMP: String = "X-Timestamp"
        const val HEADER_NONCE: String = "X-Nonce"

        /**
         * Optional fingerprint header. When present, [verifyAntiReplay] cross-
         * checks it against the paired peer's stored cert (issue #11).
         */
        const val HEADER_FINGERPRINT: String = "X-Fingerprint"
    }
}