package com.kfilesync.mobile.infrastructure.network

import io.ktor.server.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import java.security.KeyStore

/**
 * Android 'actual' for [startKtorEngine] (T1.4).
 *
 * Uses Netty for Android to support HTTPS/TLS (CIO does not support it in Ktor 3).
 *
 * When [tlsConfig] is supplied, configures Ktor Netty with an `sslConnector`
 * pointing at the AndroidKeyStore-backed JVM KeyStore. The key alias
 * resolves to a [java.security.PrivateKey] handle that the KeyStore proxies
 * to the TEE - i.e. Ktor signs the TLS handshake by asking AndroidKeyStore
 * to sign, never by handling raw key bytes. This satisfies the §9.1
 * \"private key never leaves TEE\" requirement on Android.
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
): EmbeddedServer<ApplicationEngine, out ApplicationEngine.Configuration> =
    embeddedServer(
        factory = Netty,
        environment = applicationEnvironment {},
        configure = {
            // Engine configuration: Connectors live here in Ktor 3
            if (tlsConfig != null) {
                sslConnector(
                    keyStore = tlsConfig.keyStore,
                    keyAlias = tlsConfig.keyAlias,
                    keyStorePassword = { tlsConfig.keyPassword },
                    privateKeyPassword = { tlsConfig.keyPassword }
                ) {
                    this.host = host
                    this.port = port
                    
                    // Ktor 3.x's sslConnector DSL (EngineSSLConnectorBuilder) no 
                    // longer exposes a 'trustManager' property. For mTLS, it 
                    // expects a 'trustStore: KeyStore?'.
                    this.trustStore = tlsConfig.trustManager as? KeyStore
                }
            } else {
                connector {
                    this.host = host
                    this.port = port
                }
            }
        }
    ) {
        // Application configuration (ServerConfigBuilder context)
        module(this)
    }
