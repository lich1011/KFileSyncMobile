package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.platform.AndroidKeyStoreAdapter
import com.kfilesync.mobile.platform.tls.PinningTrustManager
import java.security.KeyStore as JavaKeyStore
import javax.net.ssl.TrustManager

/**
 * Android `actual` of [HttpServerTlsConfig] (T1.4).
 *
 * Holds everything Ktor CIO's `sslConnector { ... }` needs to terminate TLS:
 *
 * - [keyStore]     : the AndroidKeyStore-backed JVM KeyStore (loaded with
 * `KeyStore.getInstance("AndroidKeyStore")`). Already
 * contains the device's EC keypair + cert under
 * [AndroidKeyStoreAdapter.LOCAL_IDENTITY_ALIAS] thanks
 * to [com.kfilesync.mobile.platform.AndroidDeviceIdentityProviderImpl].
 * - [keyAlias]     : the entry inside that KeyStore.
 * - [keyPassword]  : a no-op on AndroidKeyStore (the key is unlock-gated by
 * the OS, not a password) but Ktor's API requires it;
 * we pass an empty char array.
 * - [trustManager] : a [PinningTrustManager] for peer (client) cert
 * pinning when mTLS is desired. Phase 1 doesn't
 * require client certs - the trust manager is wired
 * for completeness so we accept any client and the
 * server side identity is what's authenticated. Phase
 * 2 will gate this on a config flag.
 *
 * The factory [load] reads from AndroidKeyStore lazily so callers don't
 * have to do the JCA dance.
 */
actual class HttpServerTlsConfig(
    val keyStore: JavaKeyStore,
    val keyAlias: String,
    val keyPassword: CharArray,
    val trustManager: TrustManager
) {
    companion object {
        fun load(pinningTrustManager: PinningTrustManager): HttpServerTlsConfig {
            val ks = JavaKeyStore.getInstance(AndroidKeyStoreAdapter.KEYSTORE_PROVIDER).apply { load(null) }
            return HttpServerTlsConfig(
                keyStore = ks,
                keyAlias = AndroidKeyStoreAdapter.LOCAL_IDENTITY_ALIAS,
                keyPassword = CharArray(0),
                trustManager = pinningTrustManager
            )
        }
    }
}