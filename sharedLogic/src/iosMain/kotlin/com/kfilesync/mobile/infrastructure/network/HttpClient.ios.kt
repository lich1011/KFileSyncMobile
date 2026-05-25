package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.platform.tls.IosPinningChallengeHandler
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig

/**
 * iOS Ktor engine - Darwin sits on top of URLSession, which gives us:
 * * native TLS handshake via Security.framework
 * * full integration with App Transport Security policies
 * * a URLSessionDelegate hook for fingerprint pinning (T1.4)
 */
internal actual fun httpClientEngine(): HttpClientEngineFactory<*> = Darwin

/**
 * Builds a Darwin engine configured with the [IosPinningChallengeHandler] (T1.4).
 * Symmetric with Android's `pinnedHttpClientEngine`: every outbound HTTPS
 * connection goes through fingerprint pinning against the paired-device
 * set. URLSession itself still performs the TLS 1.3 handshake - we just
 * override the trust decision in the `handleChallenge` block.
 *
 * Note: Darwin engine handleChallenge signature in Ktor 3.1 is
 * `(NSURLSession, NSURLSessionTask, NSURLAuthenticationChallenge,
 * (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit) -> Unit`.
 */
fun pinnedHttpClientEngine(
    deviceRepository: DeviceRepository
): HttpClientEngineFactory<DarwinClientEngineConfig> {
    val handler = IosPinningChallengeHandler(deviceRepository)
    return object : HttpClientEngineFactory<DarwinClientEngineConfig> {
        override fun create(block: DarwinClientEngineConfig.() -> Unit) = Darwin.create {
            block()
            handleChallenge { _, _, challenge, completionHandler ->
                handler.handle(challenge) { disposition, credential ->
                    completionHandler(disposition, credential)
                }
            }
        }
    }
}