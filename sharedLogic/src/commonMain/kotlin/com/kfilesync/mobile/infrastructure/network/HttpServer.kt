package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.ErrorDto
import com.kfilesync.mobile.application.dto.PairConfirmDto
import com.kfilesync.mobile.application.dto.PairRequestDto
import com.kfilesync.mobile.application.dto.PairResultDto
import com.kfilesync.mobile.application.dto.PairRevokeDto
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.PairingService
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import io.github.aakira.napier.Napier
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIOApplicationEngine
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

/**
 * Embedded HTTP(S) server exposing the lansync v1 REST surface on the device.
 * * Symmetric zero-trust wiring (T1.4):
 * * Android (sslConnector path):
 * - [HttpServer] bound to 0.0.0.0:53317 with a non-null [tlsConfig].
 * - Ktor CIO's sslConnector uses the AndroidKeyStore-resident keypair
 * via a [java.security.KeyStore] loaded from the "AndroidKeyStore"
 * provider - Ktor signs the handshake by asking the TEE to sign;
 * raw private-key bytes never enter user space.
 * * iOS (sidecar path):
 * - [HttpServer] bound to 127.0.0.1:53318 plaintext (tlsConfig = null).
 * - A separate [com.kfilesync.mobile.platform.tls.IosTlsListener] (lives in
 * iosMain) binds the public port 53317 with TLS terminated via
 * `nw_listener` + `sec_protocol_options_set_local_identity`, pulling
 * the SecIdentity from the Keychain. It pipes decrypted bytes to this
 * loopback engine. The private key never leaves the Secure Enclave.
 * * Both produce a TLS-1.3-only externally-visible endpoint backed by the
 * same self-signed cert that peers pin via the pairing flow.
 */
class HttpServer(
    private val identityProvider: LocalIdentityProvider,
    private val pairingService: PairingService? = null,
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    /**
     * Non-null on Android (real TLS via sslConnector); null on iOS (TLS
     * lives in the listener sidecar). See class KDoc for the architecture.
     */
    private val tlsConfig: HttpServerTlsConfig? = null
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

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
                }
            }
        }
        // engine?.start(wait = false) // 注：底部边缘遮挡代码提示
    }

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
        /**
         * Loopback port the iOS Ktor engine binds to; the public port 53317
         * is owned by [com.kfilesync.mobile.platform.tls.IosTlsListener].
         */
        const val IOS_LOOPBACK_PORT: Int = 53318
    }
}

private fun DevicePlatform.toWire(): String = when (this) {
    DevicePlatform.Windows -> "windows"
    DevicePlatform.MacOS -> "macos"
    DevicePlatform.Linux -> "linux"
    DevicePlatform.Android -> "android"
    DevicePlatform.IOS -> "ios"
}