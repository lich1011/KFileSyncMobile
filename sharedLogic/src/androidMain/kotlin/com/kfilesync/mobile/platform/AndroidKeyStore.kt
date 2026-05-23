package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.port.KeyStore
import io.github.aakira.napier.Napier
import java.security.KeyStore as JavaKeyStore

/**
 * AndroidKeyStore-backed [KeyStore] adapter (T1.1).
 *
 * The contract on this adapter is intentionally lean; we never need to
 * surface raw private-key bytes to the rest of the app, because the key
 * stays inside the StrongBox / TEE. Code that wants to *use* the key
 * (sign a TLS handshake, sign a cert) loads the key handle directly from
 * `AndroidKeyStore` and lets the platform driver do the signing.
 *
 * That said, the cross-platform [KeyStore] interface still has
 * `loadPrivateKey(): ByteArray`. We treat that as a "not supported on
 * Android - the key is hardware-locked" contract violation rather than
 * silently dumping the key. Callers who need the key on Android must
 * instead reach for the AndroidKeyStore directly via the alias
 * [aliasFor].
 *
 * Used by [AndroidDeviceIdentityProviderImpl] to delete the entry as part
 * of [wipe()]. Most other code paths talk to the higher-level
 * [com.kfilesync.mobile.domain.port.DeviceIdentityProvider].
 */
class AndroidKeyStoreAdapter : KeyStore {

    /**
     * On Android the private key is generated *inside* the KeyStore via
     * `KeyPairGenerator` configured with `KeyGenParameterSpec`. There is no
     * "import raw bytes" flow that wouldn't break the security contract
     * (the whole point of the TEE is that key material never leaves it).
     * If you find yourself wanting to call this, you should be using
     * [com.kfilesync.mobile.domain.port.DeviceIdentityProvider.loadOrCreateGenerate]
     * instead.
     */
    override fun storePrivateKey(id: DeviceId, key: ByteArray) {
        throw UnsupportedOperationException(
            "Android private keys are hardware-bound and cannot be imported as bytes. " +
                    "Use DeviceIdentityProvider.loadOrCreateGenerate(...) instead."
        )
    }

    override fun loadPrivateKey(id: DeviceId): ByteArray {
        throw UnsupportedOperationException(
            "Android private keys are hardware-bound. Use AndroidKeyStore.getEntry(alias) " +
                    "directly or the platform's signing APIs."
        )
    }

    override fun deletePrivateKey(id: DeviceId) {
        runCatching {
            val ks = JavaKeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val alias = aliasFor(id)
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias)
                Napier.i("AndroidKeyStore deleted entry $alias")
            }
        }.onFailure {
            Napier.w("AndroidKeyStore deleteEntry failed", it)
        }
    }

    companion object {
        const val KEYSTORE_PROVIDER: String = "AndroidKeyStore"

        /** Stable alias derived from the DeviceId. */
        fun aliasFor(id: DeviceId): String = "kfilesync:" + id.value

        /** The single alias used for our local device identity (before the DeviceId is computed). */
        const val LOCAL_IDENTITY_ALIAS: String = "kfilesync:local"
    }
}