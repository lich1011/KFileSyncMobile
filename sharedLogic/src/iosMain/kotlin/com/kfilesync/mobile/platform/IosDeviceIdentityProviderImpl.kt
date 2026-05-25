package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.port.DeviceCryptoIdentity
import com.kfilesync.mobile.domain.port.DeviceIdentityProvider
import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.CFBridgingRelease
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.date
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithAbbreviation
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeEC
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef

/**
 * iOS implementation of [DeviceIdentityProvider] (T1.1).
 *
 * Key generation:
 * - `SecKeyCreateRandomKey` with `kSecAttrKeyType=EC`, `kSecAttrKeySizeInBits=256`,
 * `kSecAttrIsPermanent=true`, and a stable `kSecAttrApplicationTag` so the
 * key persists in the iOS Keychain across app launches. The Secure Enclave
 * is *not* requested explicitly (which would need a kSecAttrTokenID flag
 * and stricter access controls); Phase 1 keeps the key Keychain-resident
 * which still prevents extraction without unlock.
 *
 * Certificate minting:
 * - We assemble the X.509 v3 tbsCertificate via [MinimalAsn1].
 * - The subjectPublicKeyInfo carries the EC public key in X9.63 form
 * (`SecKeyCopyExternalRepresentation` on the public half).
 * - We sign the SHA-256 of the tbsCertificate DER using
 * `SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256, ...)`.
 * The signature is already ASN.1 DER-encoded (a SEQUENCE of {r, s}),
 * so it can be wrapped directly into the cert's signatureValue BIT
 * STRING.
 *
 * DeviceId: SHA-256 of the final cert DER, lowercase hex - same scheme as
 * Android and desktop.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDeviceIdentityProviderImpl : DeviceIdentityProvider {

    override suspend fun loadOrGenerate(alias: String): DeviceCryptoIdentity =
        withContext(Dispatchers.Default) {
            val (privateKey, publicKey) = loadOrCreateKeyPair()
            try {
                val publicKeyData = publicKey.exportX963()
                val certDer = signSelfCert(alias, publicKeyData, privateKey)
                certToIdentity(certDer)
            } finally {
                CFRelease(privateKey)
                CFRelease(publicKey)
            }
        }

    override suspend fun wipe() = withContext(Dispatchers.Default) {
        memScoped {
            val tagData = IosKeychainAdapter.LOCAL_IDENTITY_TAG.toNSData()
            val query = mapOf<Any?, Any?>(
                kSecClass to kSecClassKey,
                kSecAttrKeyClass to kSecAttrKeyClassPrivate,
                kSecAttrApplicationTag to tagData
            )
            @Suppress("UNCHECKED_CAST")
            val status = SecItemDelete(query as CFDictionaryRef)
            if (status != 0 && status != -25300) { // -25300 is errSecItemNotFound
                Napier.w("wipe SecItemDelete status=$status")
            }
        }
    }

    // -------------------- Key generation / loading --------------------

    /**
     * Either fetch the existing local-identity private key from the Keychain
     * or generate a new one. Returns (private, public). The CFRetained refs
     * must be CFReleased by the caller.
     */
    private fun loadOrCreateKeyPair(): Pair<SecKeyRef, SecKeyRef> = memScoped {
        val tagData = IosKeychainAdapter.LOCAL_IDENTITY_TAG.toNSData()
        val findQuery = mapOf<Any?, Any?>(
            kSecClass to kSecClassKey,
            kSecAttrKeyClass to kSecAttrKeyClassPrivate,
            kSecAttrApplicationTag to tagData,
            kSecAttrKeyType to kSecAttrKeyTypeEC,
            kSecReturnRef to true
        )

        val out = allocPointerTo<CFTypeRefVar>()
        @Suppress("UNCHECKED_CAST")
        val findStatus = SecItemCopyMatching(findQuery as CFDictionaryRef, out.ptr.reinterpret())
        val privateKey: SecKeyRef = if (findStatus == 0 && out.value != null) {
            Napier.i("iOS Keychain: reusing local identity")
            out.value!!.reinterpret()
        } else {
            Napier.i("iOS Keychain: generating new local identity")
            val privAttrs = mapOf<Any?, Any?>(
                kSecAttrIsPermanent to true,
                kSecAttrApplicationTag to tagData
            )
            val attrs = mapOf<Any?, Any?>(
                kSecAttrKeyType to kSecAttrKeyTypeEC,
                kSecAttrKeySizeInBits to 256L,
                kSecPrivateKeyAttrs to privAttrs
            )

            val errVar = alloc<CFErrorRefVar>()
            @Suppress("UNCHECKED_CAST")
            val key = SecKeyCreateRandomKey(attrs as CFDictionaryRef, errVar.ptr)
                ?: error("SecKeyCreateRandomKey failed: error=${errVar.value}")
            key
        }

        val publicKey = SecKeyCopyPublicKey(privateKey)
            ?: error("SecKeyCopyPublicKey failed")
        Pair(privateKey, publicKey)
    }

    /**
     * Export the EC public key in X9.63 uncompressed form (65 bytes:
     * 0x04 || X(32) || Y(32)) — exactly what goes inside the SubjectPublicKeyInfo BIT STRING.
     */
    private fun SecKeyRef.exportX963(): ByteArray = memScoped {
        val errVar = alloc<CFErrorRefVar>()
        val cfData = SecKeyCopyExternalRepresentation(this@exportX963, errVar.ptr)
            ?: error("SecKeyCopyExternalRepresentation failed: ${errVar.value}")
        try {
            (CFBridgingRelease(cfData) as NSData).toByteArray()
        } catch (t: Throwable) {
            CFRelease(cfData)
            throw t
        }
    }

    // -------------------- Certificate assembly --------------------

    private fun signSelfCert(alias: String, pubKeyX963: ByteArray, privateKey: SecKeyRef): ByteArray {
        // ---- 1. Build subjectPublicKeyInfo (SPKI) ----
        // SPKI ::= SEQUENCE { algorithm AlgorithmIdentifier, subjectPublicKey BIT STRING }
        // AlgorithmIdentifier ::= SEQUENCE { algorithm OBJECT IDENTIFIER, parameters ANY DEFINED BY algorithm OPTIONAL }
        val algIdEcPublicKey = MinimalAsn1.sequence(
            MinimalAsn1.oid("1.2.840.10045.2.1"),   // id-ecPublicKey
            MinimalAsn1.oid("1.2.840.10045.3.1.7")  // prime256v1 / secp256r1
        )
        val spki = MinimalAsn1.sequence(algIdEcPublicKey, MinimalAsn1.bitString(pubKeyX963))

        // ---- 2. Build subject / issuer ----
        // Issuer = Subject (self-signed). DN: CN=$alias, O=KFileSync, OU=Mobile.
        val dn = MinimalAsn1.sequence(
            // RDN: CN
            MinimalAsn1.set(MinimalAsn1.sequence(MinimalAsn1.oid("2.5.4.3"), MinimalAsn1.utf8String(alias))),
            // RDN: O
            MinimalAsn1.set(MinimalAsn1.sequence(MinimalAsn1.oid("2.5.4.10"), MinimalAsn1.utf8String("KFileSync"))),
            // RDN: OU
            MinimalAsn1.set(MinimalAsn1.sequence(MinimalAsn1.oid("2.5.4.11"), MinimalAsn1.utf8String("Mobile")))
        )

        // ---- 3. Validity ----
        val notBeforeStr = formatUtcTime(NSDate.dateWithTimeIntervalSinceNow(-5 * 60.0))
        val notAfterStr = formatUtcTime(NSDate.dateWithTimeIntervalSinceNow(10.0 * 365 * 24 * 3600))
        val validity = MinimalAsn1.sequence(
            MinimalAsn1.utcTime(notBeforeStr),
            MinimalAsn1.utcTime(notAfterStr)
        )

        // ---- 4. Version + serial ----
        // Version: [0] EXPLICIT INTEGER { 2 == v3 }
        val version = MinimalAsn1.ctxConstructed(0, MinimalAsn1.integer(2L))
        // Serial: 8 random-ish bytes derived from now.
        val serialBytes = serialFromNow()
        val serial = MinimalAsn1.integer(serialBytes)

        // ---- 5. Signature algorithm in tbs (ecdsa-with-SHA256) ----
        // RFC 5758 §3.2: parameters MUST be absent.
        val sigAlg = MinimalAsn1.sequence(MinimalAsn1.oid("1.2.840.10045.4.3.2"))

        // ---- 6. Extensions [3] ----
        // basicConstraints: CA:FALSE -- 2.5.29.19
        val basicConstraints = MinimalAsn1.sequence(
            MinimalAsn1.oid("2.5.29.19"),
            MinimalAsn1.boolean(true),
            MinimalAsn1.octetString(MinimalAsn1.sequence()) // empty SEQUENCE + CA=false default
        )

        // keyUsage: digitalSignature + keyEncipherment -- 2.5.29.15
        // KU bit string: 0b10100000 => 0xA0, 5 unused bits.
        val keyUsageValue = byteArrayOf(0x05, 0xA0.toByte())
        val keyUsageBitString = byteArrayOf(0x03, 0x02) + keyUsageValue
        val keyUsage = MinimalAsn1.sequence(
            MinimalAsn1.oid("2.5.29.15"),
            MinimalAsn1.boolean(true),
            MinimalAsn1.octetString(keyUsageBitString)
        )

        // extKeyUsage: serverAuth + clientAuth -- 2.5.29.37
        val extKeyUsageInner = MinimalAsn1.sequence(
            MinimalAsn1.oid("1.3.6.1.5.5.7.3.1"),
            MinimalAsn1.oid("1.3.6.1.5.5.7.3.2")
        )
        val extKeyUsage = MinimalAsn1.sequence(
            MinimalAsn1.oid("2.5.29.37"),
            MinimalAsn1.octetString(extKeyUsageInner)
        )

        val extensions = MinimalAsn1.ctxConstructed(
            3,
            MinimalAsn1.sequence(basicConstraints, keyUsage, extKeyUsage)
        )

        // ---- 7. Assemble tbsCertificate ----
        val tbs = MinimalAsn1.sequence(
            version, serial, sigAlg, dn, validity, dn, spki, extensions
        )

        // ---- 8. Sign tbs ----
        // SecKeyCreateSignature with .ecdsaSignatureMessageX962SHA256 hashes
        // and signs in one shot; the returned data is already a DER-encoded
        // SEQUENCE { INTEGER r, INTEGER s }.
        val tbsNsData = tbs.toNSData()
        val errVar = memScoped { alloc<CFErrorRefVar>() }
        val sigCfData = SecKeyCreateSignature(
            privateKey,
            kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
            tbsNsData as CFDataRef,
            errVar.ptr
        ) ?: error("SecKeyCreateSignature failed")

        val sigBytes = try {
            (CFBridgingRelease(sigCfData) as NSData).toByteArray()
        } catch (t: Throwable) {
            CFRelease(sigCfData)
            throw t
        }

        // ---- 9. Build the outer Certificate SEQUENCE ----
        return MinimalAsn1.sequence(tbs, sigAlg, MinimalAsn1.bitString(sigBytes))
    }

    // -------------------- Plumbing --------------------

    private fun certToIdentity(certDer: ByteArray): DeviceCryptoIdentity {
        val fpHex = HashProvider.sha256(certDer).toHexLower()
        val deviceId = DeviceId(fpHex)
        val fingerprint = Fingerprint(fpHex)
        val pem = pemEncode(certDer)
        return DeviceCryptoIdentity(deviceId = deviceId, certificatePem = pem, fingerprint = fingerprint)
    }

    private fun pemEncode(der: ByteArray): String {
        val base64 = der.toBase64()
        val wrapped = base64.chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$wrapped\n-----END CERTIFICATE-----\n"
    }

    /** Last 8 bytes of current ms-since-epoch as serial bytes. */
    private fun serialFromNow(): ByteArray {
        val ms = (NSDate.date().timeIntervalSince1970 * 1000.0).toLong()
        return ByteArray(8) { i -> ((ms ushr ((7 - i) * 8)) and 0xFF).toByte() }
    }

    /** UTCTime YYMMDDHHMMSSZ in GMT. */
    private fun formatUtcTime(date: NSDate): String {
        val fmt = NSDateFormatter().apply {
            dateFormat = "yyMMddHHmmss'Z'"
            timeZone = NSTimeZone.timeZoneWithAbbreviation("UTC")!!
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
        }
        return fmt.stringFromDate(date)
    }
}

// --- Helpers shared between IosKeychainAdapter and the identity provider ---
// (see IosInteropHelpers.kt for toNSData / toByteArray)

private fun ByteArray.toBase64(): String {
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val out = StringBuilder()
    var i = 0
    while (i < size) {
        val b0 = this[i].toInt() and 0xFF
        val b1 = if (i + 1 < size) this[i + 1].toInt() and 0xFF else -1
        val b2 = if (i + 2 < size) this[i + 2].toInt() and 0xFF else -1

        val c0 = b0 ushr 2
        val c1 = ((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)
        val c2 = if (b1 >= 0) ((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0) else -1
        val c3 = if (b2 >= 0) b2 and 0x3F else -1

        out.append(table[c0]); out.append(table[c1])
        out.append(if (c2 >= 0) table[c2] else '=')
        out.append(if (c3 >= 0) table[c3] else '=')
        i += 3
    }
    return out.toString()
}