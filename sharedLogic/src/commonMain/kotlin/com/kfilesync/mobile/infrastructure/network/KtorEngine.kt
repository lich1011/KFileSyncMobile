package com.kfilesync.mobile.infrastructure.network

import io.ktor.server.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer

/**
 * Platform-specific server-engine bootstrap (T1.4).
 * * Android `actual` configures Ktor Netty with [io.ktor.server.engine.sslConnector]
 * - TLS is terminated *here* using the AndroidKeyStore-resident keypair, so
 * the private key never leaves the TEE.
 * * iOS `actual` binds the engine to plaintext loopback (127.0.0.1:port). On
 * iOS the public TLS-terminating front-end is a separate
 * [com.kfilesync.mobile.platform.tls.IosTlsListener] (nw_listener with
 * sec_protocol_options_set_local_identity), which then
 * forwards plaintext to this loopback engine - also keeps the private key
 * Secure-Enclave-resident.
 * * Both paths produce a TLS-1.3-only externally-visible endpoint backed by
 * the same self-signed cert that peers pin via the pairing flow.
 * Parameters:
 * - [tlsConfig]: platform TLS bundle. On Android non-null is required to
 * activate sslConnector; on iOS this is unused (TLS lives in the
 * listener sidecar).
 * - [port]: the port the Ktor engine binds to. Android: public 53317.
 * iOS: loopback 53318.
 * - [host]: bind host. Android: "0.0.0.0", iOS: "127.0.0.1".
 * - [module]: standard Ktor application module - installs plugins, sets
 * up routing.
 */
internal expect fun startKtorEngine(
    tlsConfig: HttpServerTlsConfig?,
    port: Int,
    host: String,
    module: Application.() -> Unit
): EmbeddedServer<ApplicationEngine, out ApplicationEngine.Configuration>
