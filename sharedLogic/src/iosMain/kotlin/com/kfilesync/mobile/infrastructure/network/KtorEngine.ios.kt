package com.kfilesync.mobile.infrastructure.network

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * iOS `actual` for [startKtorEngine] (T1.4).
 *
 * iOS Ktor CIO server runs *only* on plaintext loopback (127.0.0.1:53318).
 * TLS termination is handled outside the engine by
 * [com.kfilesync.mobile.platform.tls.IosTlsListener], which binds the
 * public port (53317) with the Secure-Enclave-resident SecIdentity and
 * pipes decrypted bytes to this engine. The [tlsConfig] parameter is
 * therefore ignored on iOS; callers always pass null.
 *
 * We use the short-form `embeddedServer(CIO, port=..., host=...)` here -
 * Ktor's Native CIO supports it for non-TLS connectors. Ktor CIO's
 * sslConnector path is JVM-only and isn't available on Kotlin/Native,
 * which is exactly why we route iOS TLS through the listener sidecar.
 */
internal actual fun startKtorEngine(
    tlsConfig: HttpServerTlsConfig?,
    port: Int,
    host: String,
    module: Application.() -> Unit
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = port, host = host, module = module)