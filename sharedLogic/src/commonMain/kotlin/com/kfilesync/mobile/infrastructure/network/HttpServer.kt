package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.BlocksRequestDto
import com.kfilesync.mobile.application.dto.BlocksResponseDto
import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.ErrorDto
import com.kfilesync.mobile.application.dto.RegisterRequestDto
import com.kfilesync.mobile.application.dto.RegisterResponseDto
import com.kfilesync.mobile.application.dto.ShareLeaveDto
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
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.service.AntiReplayGuard
import com.kfilesync.mobile.domain.service.PairedDevice
import com.kfilesync.mobile.domain.service.TrustDecision
import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import io.github.aakira.napier.Napier
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Embedded HTTP(S) server exposing the lansync v1 REST surface on the device.
 *
 * Phase 2 (T2.2 / T2.3) adds the transfer routes:
 * - `POST /transfer/request` - peer asks to send us files.
 * - `POST /transfer/{jobId}/chunk/{fileId}/{chunkIndex}` - peer streams chunks
 *   as raw bytes, expected hash carried via `X-Chunk-Hash` (Sprint 4;
 *   matches the desktop client's route shape for interop).
 * - `POST /transfer/cancel` - peer aborts an in-flight job.
 *
 * Symmetric zero-trust wiring (T1.4) - see class history.
 *
 * Phase 5 (T5.3) adds the anti-replay layer: every *mutating* route requires
 * three headers - `X-Device-Id`, `X-Timestamp` (Unix epoch millis), and
 * `X-Nonce` - and we ask [AntiReplayGuard] to decide if the request is from a
 * trusted, non-replaying peer. Routes that fail the check answer with
 * `401 Unauthorized` and do not touch the application services.
 *
 * Read-only routes (`GET /info`, `GET /healthz`, `GET /sync/index`) are
 * excluded - they're idempotent + already TLS+fingerprint-pinned. Replaying
 * a GET buys an attacker nothing they couldn't already get from a fresh
 * pinned connection.
 *
 * Sprint 5 extracted the per-route `verifyAntiReplay(call)` guard calls into
 * a single `TrustPlugin` (installed once, applies to every non-GET request)
 * - see [start]. The pairing-bootstrap exemption (`/pair/request`,
 * `/pair/confirm`, `/register` accept unpaired callers) lives inside
 * [AntiReplayGuard.evaluate] itself (`TrustDecision.AllowUnpairedForPairing`),
 * not in this class, so extracting the call site changes nothing about which
 * routes are exempt.
 */
class HttpServer(
    private val identityProvider: LocalIdentityProvider,
    private val pairingService: PairingService? = null,
    private val transferService: TransferAppService? = null,
    private val shareService: ShareAppService? = null,
    private val fileIndexRepository: FileIndexRepository? = null,
    private val shareRepository: ShareRepository? = null,
    private val deviceRepository: DeviceRepository? = null,
    private val antiReplayGuard: AntiReplayGuard? = null,
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    private val tlsConfig: HttpServerTlsConfig? = null,
    /**
     * Test escape hatch; permit running without an [AntiReplayGuard]. Production
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
                allowHeader(HEADER_FINGERPRINT)
                allowHeader(HEADER_CHUNK_HASH)
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

            // TrustPlugin (Sprint 5): runs before routing resolves a handler.
            // GET routes are read-only/idempotent and already
            // TLS+fingerprint-pinned - see class doc comment. Every other
            // route must pass anti-replay + pairing-trust verification
            // before its handler runs; `finish()` short-circuits the
            // pipeline so a rejected call never reaches the route body.
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.httpMethod != HttpMethod.Get) {
                    if (!verifyAntiReplay(call)) {
                        finish()
                    }
                }
            }

            routing {
                route(WireConstants.apiPrefix) {

                    // ----- Identity -----
                    // Sprint 4 follow-up (ADR-018): bodies for the 11 routes
                    // below now travel as raw bytes via WireCodec.encodeX/
                    // decodeX, which map to/from core's UniFFI DTO + call the
                    // core-exported parse_*/encode_* codec (single source of
                    // truth for the JSON shape). Routes whose only DTOs have
                    // no core equivalent (register, sync/blocks, and the
                    // *ResultDto responses) keep using the implicit
                    // ContentNegotiation JSON path unchanged.
                    get(WireConstants.routeInfo.removePrefix(WireConstants.apiPrefix)) {
                        val identity = identityProvider.current()
                        call.respondBytes(
                            WireCodec.encodeDeviceInfo(
                                DeviceInfoDto(
                                    deviceId = identity.deviceId.value,
                                    alias = identity.alias,
                                    deviceType = "mobile",
                                    platform = identity.platform.toWire(),
                                    fingerprint = identity.fingerprint.hex,
                                    port = identity.port,
                                    announce = true
                                )
                            ),
                            ContentType.Application.Json
                        )
                    }

                    get(WireConstants.routeHealthz.removePrefix(WireConstants.apiPrefix)) {
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    }

                    // ----- Pairing (T1.3) -----
                    post(WireConstants.routePairRequest.removePrefix(WireConstants.apiPrefix)) {
                        val svc = pairingService
                        if (svc == null) {
                            call.respondBytes(
                                WireCodec.encodePairResult(
                                    PairResultDto(requestId = "", accepted = false, reason = "pairing not configured")
                                ),
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable
                            )
                            return@post
                        }
                        // Pairing routes are the bootstrap channel; the peer is by
                        // definition not yet paired, so we can't gate on the device
                        // repo. The PIN exchange itself authenticates.
                        val body = WireCodec.decodePairRequest(call.receive<ByteArray>())
                        svc.receiveIncoming(body)
                        // Pending state per dto.rs; accepted=false, no cert/reason yet -
                        // the real accept/reject happens on /pair/confirm.
                        call.respondBytes(
                            WireCodec.encodePairResult(PairResultDto(requestId = body.requestId, accepted = false)),
                            ContentType.Application.Json,
                            HttpStatusCode.Accepted
                        )
                    }

                    post(WireConstants.routePairConfirm.removePrefix(WireConstants.apiPrefix)) {
                        val svc = pairingService
                        if (svc == null) {
                            call.respondBytes(
                                WireCodec.encodePairResult(
                                    PairResultDto(requestId = "", accepted = false, reason = "pairing not configured")
                                ),
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable
                            )
                            return@post
                        }
                        val body = WireCodec.decodePairConfirm(call.receive<ByteArray>())
                        val outcome = svc.confirmIncoming(body)
                        if (outcome.result.isSuccess) {
                            call.respondBytes(
                                WireCodec.encodePairResult(
                                    PairResultDto(
                                        requestId = body.requestId,
                                        accepted = true,
                                        peerCertificatePem = outcome.ourCertPem
                                    )
                                ),
                                ContentType.Application.Json
                            )
                        } else {
                            call.respondBytes(
                                WireCodec.encodePairResult(
                                    PairResultDto(
                                        requestId = body.requestId,
                                        accepted = false,
                                        reason = outcome.result.exceptionOrNull()?.message
                                    )
                                ),
                                ContentType.Application.Json,
                                HttpStatusCode.Forbidden
                            )
                        }
                    }

                    post(WireConstants.routePairRevoke.removePrefix(WireConstants.apiPrefix)) {
                        val svc = pairingService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ErrorDto(code = "pairing_not_configured", message = "pairing not configured")
                            )
                            return@post
                        }
                        val body = WireCodec.decodePairRevoke(call.receive<ByteArray>())
                        val outcome = svc.revoke(DeviceId(body.deviceId))
                        if (outcome.isSuccess) {
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        } else {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorDto(
                                    code = "device_not_found",
                                    message = outcome.exceptionOrNull()?.message ?: "device not found"
                                )
                            )
                        }
                    }

                    // ----- Transfer (T2.2 / T2.3) -----
                    post(WireConstants.routeTransferRequest.removePrefix(WireConstants.apiPrefix)) {
                        val svc = transferService
                        if (svc == null) {
                            call.respondBytes(
                                WireCodec.encodeTransferAccept(
                                    TransferAcceptDto(
                                        sessionId = "",
                                        jobId = "",
                                        accepted = false,
                                        reason = "transfer service not configured"
                                    )
                                ),
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable
                            )
                            return@post
                        }
                        val body = WireCodec.decodeTransferRequest(call.receive<ByteArray>())
                        val resp = svc.onTransferRequest(body)
                        call.respondBytes(
                            WireCodec.encodeTransferAccept(resp),
                            ContentType.Application.Json,
                            if (resp.accepted) HttpStatusCode.OK else HttpStatusCode.Forbidden
                        )
                    }

                    post("/transfer/{jobId}/chunk/{fileId}/{chunkIndex}") {
                        val jobId = call.parameters["jobId"].orEmpty()
                        val fileId = call.parameters["fileId"].orEmpty()
                        val chunkIndex = call.parameters["chunkIndex"]?.toIntOrNull()
                        val svc = transferService
                        if (svc == null || chunkIndex == null) {
                            // chunkIndex unknown/invalid has no valid core u32
                            // representation (core has no negative sentinel) -
                            // this error path stays on the implicit
                            // ContentNegotiation JSON path rather than forcing
                            // -1 through an unsigned wire type.
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                TransferChunkAckDto(
                                    jobId = jobId,
                                    fileId = fileId,
                                    chunkIndex = chunkIndex ?: -1,
                                    verified = false
                                )
                            )
                            return@post
                        }
                        val chunkHash = call.request.headers[HEADER_CHUNK_HASH].orEmpty()
                        val bytes = call.receive<ByteArray>()
                        val ack = svc.onTransferChunk(jobId, fileId, chunkIndex, chunkHash, bytes)
                        call.respondBytes(
                            WireCodec.encodeTransferChunkAck(ack),
                            ContentType.Application.Json,
                            if (ack.verified) HttpStatusCode.OK else HttpStatusCode.BadRequest
                        )
                    }

                    post(WireConstants.routeTransferCancel.removePrefix(WireConstants.apiPrefix)) {
                        val svc = transferService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                TransferResultDto(ok = false, error = "transfer service not configured")
                            )
                            return@post
                        }
                        val body = WireCodec.decodeTransferCancel(call.receive<ByteArray>())
                        svc.onTransferCancel(body)
                        call.respond(HttpStatusCode.OK, TransferResultDto(ok = true))
                    }

                    // ----- Share (T3.2) -----
                    post(WireConstants.routeShareInvite.removePrefix(WireConstants.apiPrefix)) {
                        val svc = shareService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ShareResultDto(ok = false, error = "share service not configured")
                            )
                            return@post
                        }
                        val body = WireCodec.decodeShareInvite(call.receive<ByteArray>())
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

                    post(WireConstants.routeShareAuthorize.removePrefix(WireConstants.apiPrefix)) {
                        val svc = shareService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ShareResultDto(ok = false, error = "share service not configured")
                            )
                            return@post
                        }
                        val body = WireCodec.decodeShareAuthorize(call.receive<ByteArray>())
                        // Anti-replay already confirmed X-Device-Id belongs to a
                        // paired peer; that peer is the invitee announcing its
                        // accept/decline, so its identity comes from the header,
                        // not the body (dto.rs: invitee -> creator).
                        val fromDeviceId = DeviceId(call.request.headers[HEADER_DEVICE_ID].orEmpty())
                        val outcome = svc.onShareAuthorize(body, fromDeviceId)
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

                    // ----- Sync (T4.5) -----
                    // NOTE: registered under the full versioned path so it matches
                    // the client (LanSyncHttpClient.getSyncIndex) and the desktop
                    // lansync v1 contract (§7.3). Previously this was mistakenly
                    // mounted at root "/sync/index", which 404'd every index fetch.
                    get(WireConstants.routeSyncIndex) {
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
                        call.respondBytes(
                            WireCodec.encodeIndexResponse(
                                IndexResponseDto(
                                    shareId = shareIdParam,
                                    // No monotonic per-share index versioning exists yet;
                                    // a wall-clock stamp is a safe placeholder since it
                                    // never falsely signals "nothing changed".
                                    indexVersion = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                                    entries = entries
                                )
                            ),
                            ContentType.Application.Json
                        )
                    }

                    // ----- Discovery registration (§7.3) -----
                    post(WireConstants.routeRegister) {
                        // Registration is a discovery-time announce; the peer may not
                        // be paired yet, so allow unpaired (the PIN ceremony is the
                        // real trust authenticator, not /register). Neither
                        // RegisterRequestDto nor RegisterResponseDto has a core
                        // equivalent (core has no /register body DTO at all), so
                        // this route keeps the implicit ContentNegotiation path -
                        // only the path literal is sourced from WireConstants.
                        val body = call.receive<RegisterRequestDto>()
                        // Only refresh state for devices we already trust; an unpaired
                        // peer gets an ack but cannot write rows into our DB.
                        val repo = deviceRepository
                        if (repo != null) {
                            val known = repo.findById(DeviceId(body.deviceId))
                            if (known != null && known.state == DeviceState.Paired) {
                                runCatching { repo.updateLastSeen(known.id, kotlin.time.Clock.System.now(), null) }
                            }
                        }
                        call.respond(HttpStatusCode.OK, RegisterResponseDto(accepted = true))
                    }

                    // ----- Share leave (notify creator) -----
                    post(WireConstants.routeShareLeave) {
                        val svc = shareService
                        if (svc == null) {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ShareResultDto(ok = false, error = "share service not configured")
                            )
                            return@post
                        }
                        val body = WireCodec.decodeShareLeave(call.receive<ByteArray>())
                        val outcome = svc.onShareLeave(body)
                        if (outcome.isSuccess) {
                            call.respond(HttpStatusCode.OK, ShareResultDto(ok = true))
                        } else {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ShareResultDto(ok = false, error = outcome.exceptionOrNull()?.message ?: "share leave rejected")
                            )
                        }
                    }

                    // ----- Sync blocks (§7.3) -----
                    // The block data-plane is carried over /transfer/chunks (Phase 4
                    // decision; see design §7.3 note). This route returns a well-formed
                    // empty answer so a strict desktop peer doesn't 404; content-defined
                    // chunk dedup remains a post-MVP item (§1.3 non-goal). No core
                    // equivalent exists for this route (path or DTOs), so it's
                    // entirely untouched by this migration.
                    post("/api/lansync/v1/sync/blocks") {
                        val body = call.receive<BlocksRequestDto>()
                        Napier.i("/sync/blocks for ${body.shareId} (${body.paths.size} paths) - served via transfer pipeline")
                        call.respond(HttpStatusCode.OK, BlocksResponseDto(blocks = emptyMap()))
                    }
                }
            }
        }
        engine?.start(wait = false)
    }

    /**
     * Anti-replay + pairing-trust header verification (T5.3, hardened against
     * issue #11).
     *
     * Delegates to [AntiReplayGuard.evaluate], which folds together three
     * checks in one call: (1) the asserted X-Device-Id must refer to a
     * currently-paired peer, unless the route is a pairing-bootstrap route
     * (the PIN exchange itself is the real authenticator there); (2) an
     * optional X-Fingerprint header must match the paired peer's cert; and
     * (3) the (timestamp, nonce) pair must be fresh and not a replay.
     *
     * Tests that need the legacy permissive behaviour set
     * [allowMissingNonceWindow] = true.
     */
    private suspend fun verifyAntiReplay(call: ApplicationCall): Boolean {
        val guard = antiReplayGuard
        if (guard == null) {
            if (allowMissingNonceWindow) {
                // Legacy / test wiring - explicit opt-in.
                return true
            }
            Napier.e(
                "HttpServer: anti-replay guard not configured - rejecting mutating request. " +
                    "Construct the server with an AntiReplayGuard or set allowMissingNonceWindow = true " +
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

        val pairedDevices = deviceRepository?.findAll()
            ?.mapNotNull { device ->
                device.certificatePem?.takeIf { device.state == DeviceState.Paired }?.let { certPem ->
                    PairedDevice(
                        deviceId = device.id.value,
                        certFingerprintHex = HashProvider.sha256(pemToDer(certPem)).toHexLower()
                    )
                }
            }
            ?: emptyList()

        val decision = guard.evaluate(
            route = call.request.path(),
            deviceIdHeader = call.request.headers[HEADER_DEVICE_ID],
            timestampMsHeader = call.request.headers[HEADER_TIMESTAMP]?.toLongOrNull(),
            nonceHeader = call.request.headers[HEADER_NONCE],
            fingerprintHeader = call.request.headers[HEADER_FINGERPRINT],
            pairedDevices = pairedDevices,
            nowMs = Clock.System.now().toEpochMilliseconds(),
            windowSizeMs = 5.minutes.inWholeMilliseconds,
            clockSkewMs = 5.minutes.inWholeMilliseconds
        )

        return when (decision) {
            is TrustDecision.Allow, TrustDecision.AllowUnpairedForPairing -> true
            is TrustDecision.Reject -> {
                Napier.w("HttpServer: anti-replay rejected (${decision.errorCode}):${decision.reason}")
                call.respond(
                    HttpStatusCode.fromValue(decision.httpStatus),
                    ErrorDto(code = decision.errorCode, message = decision.reason)
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

        /**
         * Wire-stable anti-replay header names (T5.3). Delegate to
         * [WireConstants] (ADR-018) instead of hand-copied literals, so this
         * and [LanSyncHttpClient] can never drift from core's single source
         * of truth.
         */
        val HEADER_DEVICE_ID: String get() = WireConstants.headerDeviceId
        val HEADER_TIMESTAMP: String get() = WireConstants.headerTimestamp
        val HEADER_NONCE: String get() = WireConstants.headerNonce

        /**
         * Optional fingerprint header. When present, [verifyAntiReplay] cross-
         * checks it against the paired peer's stored cert (issue #11).
         */
        val HEADER_FINGERPRINT: String get() = WireConstants.headerFingerprint

        /** Expected BLAKE3 hex of the raw chunk body (Sprint 4 chunk channel). */
        val HEADER_CHUNK_HASH: String get() = WireConstants.headerChunkHash
    }
}