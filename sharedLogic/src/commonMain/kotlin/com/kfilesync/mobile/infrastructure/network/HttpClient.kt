package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.PairConfirmDto
import com.kfilesync.mobile.application.dto.PairRequestDto
import com.kfilesync.mobile.application.dto.PairResultDto
import com.kfilesync.mobile.application.dto.PairRevokeDto
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
 * Darwin (NSURLSession) engine.
 *
 * Both look at the leaf cert's SHA-256, compare against
 * `DeviceRepository.findPaired()`, and reject on mismatch.
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
                    requestTimeoutMillis = 10_000
                    socketTimeoutMillis = 10_000
                }
            }
    }
}

internal expect fun httpClientEngine(): HttpClientEngineFactory<*>