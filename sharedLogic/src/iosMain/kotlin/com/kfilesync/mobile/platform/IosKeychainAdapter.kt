package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.port.KeyStore
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFDictionaryRef
import platform.Security.SecItemDelete
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecClass
import platform.Security.kSecClassKey

/**
 * Keychain-backed [KeyStore] adapter for iOS (T1.1).
 *
 * Like the Android adapter, the underlying private key never leaves the
 * Secure Enclave / Keychain. We therefore implement only the destructive
 * "delete" path here; key generation + signing live in
 * [IosDeviceIdentityProviderImpl] which talks to `SecKey*` directly.
 *
 * Calls to [storePrivateKey] / [loadPrivateKey] throw - code that hits
 * them is calling the wrong abstraction.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosKeychainAdapter : KeyStore {

    override fun storePrivateKey(id: DeviceId, key: ByteArray) {
        throw UnsupportedOperationException(
            "iOS Keychain-resident keys are never imported as bytes. " +
                    "Use DeviceIdentityProvider.loadOrGenerate(...) instead."
        )
    }

    override fun loadPrivateKey(id: DeviceId): ByteArray {
        throw UnsupportedOperationException(
            "iOS Keychain keys are not exportable. Use SecKeyCreateSignature " +
                    "or the platform's signing APIs."
        )
    }

    override fun deletePrivateKey(id: DeviceId) {
        val tag = tagFor(id)
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassKey,
            kSecAttrKeyClass to kSecAttrKeyClassPrivate,
            kSecAttrApplicationTag to tag.encodeToByteArray().toNSData()
        )
        @Suppress("UNCHECKED_CAST")
        val status = SecItemDelete(query as CFDictionaryRef)
        if (status != 0 && status != -25300) { /* errSecItemNotFound */
            Napier.w("SecItemDelete status=$status for ${id.value}")
        }
    }

    companion object {
        const val LOCAL_IDENTITY_TAG: String = "com.kfilesync.mobile.identity.local"

        fun tagFor(id: DeviceId): String = "com.kfilesync.mobile.identity." + id.value
    }
}