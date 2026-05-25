package com.kfilesync.mobile.infrastructure.network

/**
 * Platform-specific bundle of TLS configuration the [HttpServer] can use to
 * terminate TLS directly (T1.4).
 * * Phase 1 wiring:
 * - androidMain provides a real class holding a JVM `KeyStore`
 * (AndroidKeyStore-backed), keyAlias, and an optional `PinningTrustManager`
 * for client-cert mTLS (not enforced in Phase 1; left as an extension
 * point).
 * - iosMain provides a marker stub. On iOS we *never* terminate TLS in
 * Ktor - the [com.kfilesync.mobile.platform.tls.IosTlsListener] sidecar
 * does it via Network.framework. So the iOS actual is a placeholder
 * that callers don't instantiate; the iOS HttpServer is always wired
 * with `tlsConfig = null`.
 * * The platform variance is necessary because Ktor CIO's TLS config takes a
 * `java.security.KeyStore` which doesn't exist in Kotlin/Native at all. We
 * keep the type in commonMain so the [HttpServer] constructor signature is
 * uniform across platforms; the actual values come from each module's DI.
 */
expect class HttpServerTlsConfig