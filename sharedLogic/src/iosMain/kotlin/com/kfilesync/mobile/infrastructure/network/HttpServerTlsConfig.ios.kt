package com.kfilesync.mobile.infrastructure.network

/**
 * iOS `actual` of [HttpServerTlsConfig].
 *
 * On iOS, TLS is terminated by [com.kfilesync.mobile.platform.tls.IosTlsListener]
 * outside the Ktor server (the Ktor HttpServer is bound to 127.0.0.1 plaintext
 * and the listener pumps decrypted bytes to it). So this `actual` is a
 * marker that's never instantiated - `IosModule` always passes
 * `tlsConfig = null` to [HttpServer].
 *
 * The class still has to exist so the commonMain signature compiles.
 */
actual class HttpServerTlsConfig private constructor()