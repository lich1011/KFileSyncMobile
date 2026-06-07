package com.kfilesync.mobile.platform.tls

import com.kfilesync.mobile.platform.IosKeychainAdapter
import com.kfilesync.mobile.platform.toNSData
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecIdentityCreate
import platform.Security.SecIdentityRef
import platform.Security.SecItemCopyMatching
import platform.Security.SecKeyRef
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeEC
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecReturnRef

/**
 * Bridges the Secure-Enclave-resident private key (T1.1) and the self-signed
 * certificate we mint at first boot into a [SecIdentityRef] suitable for
 * Network.framework's `sec_identity_create` / `sec_protocol_options_set_local_identity`.
 *
 * Why this helper exists: `SecIdentity` couples a private key with its
 * corresponding cert. The Keychain stores our private key (Secure Enclave
 * handle, never extractable) and we keep the cert PEM in our app database;
 * to wire TLS server identity we need to:
 *
 * 1. Fetch the SecKeyRef from the Keychain (by stable application tag).
 * 2. Build a SecCertificateRef from the cert DER.
 * 3. Combine them via `SecIdentityCreate(allocator, cert, key)`.
 *
 * The resulting SecIdentityRef is what `sec_identity_create` wraps for the
 * TLS layer to use. The private key never leaves the Secure Enclave -
 * SecIdentity is a handle pair, not a copy.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosSecIdentityBridge {

    /**
     * Build a SecIdentityRef from the persistent local-identity entries in
     * the Keychain. Returns null if either half can't be located (e.g. the
     * device-identity provider hasn't been called yet to mint the keypair).
     */
    fun loadLocalIdentity(certificatePem: String): SecIdentityRef? = memScoped {
        val keyRef = lookupPrivateKey() ?: run {
            Napier.w("IosSecIdentityBridge: no private key in Keychain")
            return null
        }
        val certRef = buildCertificate(certificatePem) ?: run {
            Napier.w("IosSecIdentityBridge: failed to parse cert PEM")
            return null
        }
        // SecIdentityCreate(allocator, certificate, privateKey) returns a
        // retained ref. Caller owns it; we return raw and the caller
        // CFReleases via the SecIdentityRef holder pattern.
        SecIdentityCreate(null, certRef, keyRef)
    }

    private fun lookupPrivateKey(): SecKeyRef? = memScoped {
        val tagData = IosKeychainAdapter.LOCAL_IDENTITY_TAG.toNSData()
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassKey,
            kSecAttrKeyClass to kSecAttrKeyClassPrivate,
            kSecAttrApplicationTag to tagData,
            kSecAttrKeyType to kSecAttrKeyTypeEC,
            kSecReturnRef to true
        )
        val out = allocPointerTo<CFTypeRefVar>()
        @Suppress("UNCHECKED_CAST")
        val status = SecItemCopyMatching(query as CFDictionaryRef, out.ptr.reinterpret())
        if (status == 0 && out.value != null) {
            out.value as SecKeyRef
        } else {
            null
        }
    }

    private fun buildCertificate(pem: String): SecCertificateRef? {
        val der = com.kfilesync.mobile.infrastructure.crypto.PemUtils.pemToDer(pem) ?: return null
        return SecCertificateCreateWithData(null, der.toNSData() as platform.CoreFoundation.CFDataRef)
    }
}