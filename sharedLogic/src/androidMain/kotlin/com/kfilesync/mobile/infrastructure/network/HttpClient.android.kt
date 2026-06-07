package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.platform.tls.PinningTrustManager
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Android Ktor engine - OkHttp is the canonical KMP-Android choice:
 * - stable TLS handshake on every Android we care about (API 26+)
 * - pluggable TrustManager for our fingerprint-pinning strategy (T1.4)
 *
 * The plain factory below is what the Phase 0 plumbing pulled in for the
 * /info probe. Phase 1 (T1.4) introduces [pinnedHttpClientEngine] which
 * additionally installs the [PinningTrustManager] against the shared
 * [PinnedTrustSnapshot] (hardened in the security review).
 */
internal actual fun httpClientEngine(): HttpClientEngineFactory<*> = OkHttp

/**
 * Builds an OkHttp engine configured with the [PinningTrustManager] (T1.4).
 *
 * Use this for any outbound HTTPS to peers - i.e. the [LanSyncHttpClient]
 * once we wire the pair/ * and transfer/ * surfaces. The plain factory above
 * stays for tests + the bootstrap probe before any device is paired.
 *
 * Hardened against issues #7/#8/#9: the trust manager looks up pinned
 * fingerprints in [PinnedTrustSnapshot] (an atomic cache refreshed via the
 * event bus), so the TLS handshake never blocks on a SQLite read.
 */
fun pinnedHttpClientEngine(pinned: PinnedTrustSnapshot): HttpClientEngineFactory<OkHttpConfig> {
    val trustManager = PinningTrustManager(pinned)
    val sslContext: SSLContext = SSLContext.getInstance("TLSv1.3").apply {
        init(null, arrayOf<javax.net.ssl.X509TrustManager>(trustManager), SecureRandom())
    }

    return object : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine =
            OkHttp.create {
                block()
                config {
                    sslSocketFactory(sslContext.socketFactory, trustManager as X509TrustManager)
                    hostnameVerifier { _, _ ->
                        // Hostname verification is meaningless for fingerprint-
                        // pinned self-signed certs; trust is delegated entirely
                        // to the trust manager above.
                        true
                    }
                }
            }
    }
}