package com.kfilesync.mobile.infrastructure.network

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector

/**
 * Android `actual` for [startKtorEngine] (T1.4).
 *
 * When [tlsConfig] is supplied, configures Ktor CIO with an `sslConnector`
 * pointing at the AndroidKeyStore-backed JVM KeyStore. The key alias
 * resolves to a [java.security.PrivateKey] handle that the KeyStore proxies
 * to the TEE - i.e. Ktor signs the TLS handshake by asking AndroidKeyStore
 * to sign, never by handling raw key bytes. This satisfies the §9.1
 * "private key never leaves TEE" requirement on Android.
 *
 * When [tlsConfig] is null, falls back to a plaintext connector - used only
 * for tests; production Android Application always passes a non-null
 * config.
 */
internal actual fun startKtorEngine(
    tlsConfig: HttpServerTlsConfig?,
    port: Int,
    host: String,
    module: Application.() -> Unit
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, environment = io.ktor.server.engine.applicationEnvironment {}, configure = {
        if (tlsConfig != null) {
            sslConnector(
                keyStore = tlsConfig.keyStore,
                keyAlias = tlsConfig.keyAlias,
                keyStorePassword = { tlsConfig.keyPassword },
                privateKeyPassword = { tlsConfig.keyPassword }
            ) {
                this.host = host
                this.port = port
            }
        } else {
            connector {
                this.host = host
                this.port = port
            }
        }
    }, module = module)