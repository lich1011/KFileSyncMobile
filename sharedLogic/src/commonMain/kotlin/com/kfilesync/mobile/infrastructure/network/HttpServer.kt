package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.ErrorDto
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.model.DevicePlatform
import io.github.aakira.napier.Napier
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * Embedded HTTPS server exposing the lansync v1 REST surface on the device.
 *
 * Phase 0 ships a tiny but real Ktor CIO server:
 *
 * GET /api/lansync/v1/info      -> echoes our [LocalIdentity] as `DeviceInfoDto`
 * GET /api/lansync/v1/healthz   -> lightweight liveness probe used by the
 * Android foreground service / iOS BGTask
 *
 * Phase 1 (T1.3) adds the pair* and transfer* routes; Phase 2 (T2.3) adds
 * sync * routes. Phase 1 (T1.4) replaces plain HTTP with TLS 1.3 + custom
 * trust manager / URLSession delegate.
 */
class HttpServer(
    private val identityProvider: LocalIdentityProvider,
    private val port: Int = DEFAULT_PORT,
    /**
     * Bind address. '0.0.0.0' (Android) and '::' (iOS) both accept LAN traffic;
     * '127.0.0.1' is useful when the server should only be reachable locally
     * (e.g. in tests).
     */
    private val host: String = "0.0.0.0"
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Starts the server asynchronously. Returns immediately. Safe to call from any thread. */
    fun start() {
        if (engine != null) {
            Napier.w("HttpServer.start() called while already running, ignoring")
            return
        }

        Napier.i("starting HttpServer on $host:$port")
        engine = embeddedServer(CIO, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    prettyPrint = false
                })
            }

            install(CORS) {
                // LAN-only: the desktop client is on the same Wi-Fi, no need
                // for wildcard origin. But we allow any host so old desktop
                // builds with hard-coded http://localhost still work.
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
                }
            }
        }
        engine?.start(wait = false)
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
    }
}

private fun DevicePlatform.toWire(): String = when (this) {
    DevicePlatform.Windows -> "windows"
    DevicePlatform.MacOs -> "macos"
    DevicePlatform.Linux -> "linux"
    DevicePlatform.Android -> "android"
    DevicePlatform.IOS -> "ios"
}