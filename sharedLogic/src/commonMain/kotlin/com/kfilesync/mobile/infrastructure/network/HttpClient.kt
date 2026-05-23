package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.DeviceInfoDto
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Outbound HTTP client used to talk to peer devices.
 *
 * Phase 0 implements one real call - `GET /api/lansync/v1/info` against a peer
 * - so we can prove end-to-end connectivity with the desktop. Phase 1 (T1.3)
 * adds pair*, Phase 2 (T2.2) adds transfer*. Phase 1 (T1.4) swaps the bare
 * HTTP engine for a TLS-enabled one that pins fingerprints against
 * [DeviceRepository.findPaired].
 *
 * The Ktor engine is supplied via [httpClientEngine] (expect/actual) so this
 * class itself stays in commonMain. The constructor exposes the underlying
 * [HttpClient] for tests / future operations.
 */
class LanSyncHttpClient(
    private val client: HttpClient = defaultClient()
) {

    /**
     * Hits `GET /api/lansync/v1/info` on a peer at [baseUrl] (e.g.
     * "http://192.168.1.42:53317"). Returns the parsed [DeviceInfoDto] or
     * null if the peer is unreachable / responds with a non-2xx code.
     */
    suspend fun fetchInfo(baseUrl: String): DeviceInfoDto? {
        return try {
            client.get("$baseUrl/api/lansync/v1/info").body()
        } catch (t: Throwable) {
            Napier.w("fetchInfo($baseUrl) failed: ${t.message}")
            null
        }
    }

    fun close() {
        client.close()
    }

    companion object {
        private fun defaultClient(): HttpClient =
            HttpClient(httpClientEngine()) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    })
                }

                install(HttpTimeout) {
// LAN: be generous, peer may be momentarily busy or asleep.
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 10_000
                    socketTimeoutMillis = 10_000
                }
            }
    }
}

/**
 * Provides the platform-specific Ktor engine. Wired up in:
 * - androidMain -> 'OkHttp' (mature TLS handshake, AndroidKeyStore-aware)
 * - iosMain    -> 'Darwin' (NSURLSession-backed, integrates with Network.framework)
 */
internal expect fun httpClientEngine(): io.ktor.client.engine.HttpClientEngineFactory<*>