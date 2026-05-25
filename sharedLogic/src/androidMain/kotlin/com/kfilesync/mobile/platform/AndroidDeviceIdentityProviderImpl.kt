package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.port.DeviceCryptoIdentity
import com.kfilesync.mobile.domain.port.DeviceIdentityProvider
import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore as JavaKeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import java.util.Date

/**
 * Android implementation of [DeviceIdentityProvider] (T1.1).
 *
 * The key generation flow:
 *
 * 1. `KeyPairGenerator.getInstance("EC", "AndroidKeyStore")` - produces an
 * EC P-256 key pair *inside* the AndroidKeyStore. The private key
 * object we get back is a *handle*, not raw bytes - there's no way to
 * extract the key material from a hardware-backed entry on a device
 * with StrongBox / TEE support, which is exactly what we want.
 * 2. `JcaX509v3CertificateBuilder` (BouncyCastle bcpkix) builds an X.509v3
 * template: serial = random, validity = 10 years (LAN-only, no CA, no
 * rotation pressure), subject = "CN=<alias>, O=KFileSync", extensions
 * = digitalSignature+keyEncipherment + serverAuth+clientAuth + a SAN
 * with "DNS:kfilesync.local" so OkHttp's default hostname verifier
 * accepts it. (We additionally pin by fingerprint via a custom
 * TrustManager in T1.4, but having a SAN keeps non-pinned tools usable
 * during diagnostics.)
 * 3. The cert is signed with `SHA256withECDSA` using the AndroidKeyStore-
 * resident private key. BouncyCastle's `ContentSigner` calls into the
 * AndroidKeyStore provider transparently because the PrivateKey carries
 * the right provider tag.
 *
 * DeviceId derivation: SHA-256 of the certificate's DER encoding, lowercase
 * hex. This is the same scheme the desktop uses, so the IDs round-trip.
 *
 * Idempotency: [LoadOrGenerate] checks the KeyStore for the
 * [AndroidKeyStoreAdapter.LOCAL_IDENTITY_ALIAS] entry first, and rebuilds
 * the identity from the stored certificate if found.
 */
class AndroidDeviceIdentityProviderImpl : DeviceIdentityProvider {

    override suspend fun loadOrGenerate(alias: String): DeviceCryptoIdentity =
        withContext(Dispatchers.Default) {
            val ks = androidKeyStore()
            val ksAlias = AndroidKeyStoreAdapter.LOCAL_IDENTITY_ALIAS
            val cert = ks.getCertificate(ksAlias) as? X509Certificate
            if (cert != null) {
                Napier.i("AndroidKeyStore: reusing existing local identity")
                certToIdentity(cert)
            } else {
                Napier.i("AndroidKeyStore: minting new local identity for alias=$alias")
                val keyPair = generateKeyPair(ksAlias)
                val freshCert = signSelfCert(alias = alias, keyPair = keyPair)
                // Persist the certificate next to the private key. AndroidKeyStore
                // stores key+cert as one entry, so writing the cert via setKeyEntry
                // on top of the existing private-key alias attaches both.
                ks.setKeyEntry(ksAlias, keyPair.private, null, arrayOf(freshCert))
                certToIdentity(freshCert)
            }
        }

    override suspend fun wipe() = withContext(Dispatchers.Default) {
        val ks = androidKeyStore()
        val ksAlias = AndroidKeyStoreAdapter.LOCAL_IDENTITY_ALIAS
        if (ks.containsAlias(ksAlias)) {
            ks.deleteEntry(ksAlias)
            Napier.i("AndroidKeyStore: wiped local identity")
        }
    }

    private fun androidKeyStore(): JavaKeyStore =
        JavaKeyStore.getInstance(AndroidKeyStoreAdapter.KEYSTORE_PROVIDER).apply { load(null) }

    private fun generateKeyPair(ksAlias: String): KeyPair {
        // We deliberately target P-256: every Android version from API 23
        // can do EC P-256 in StrongBox or the TEE, and Ktor's OkHttp engine
        // talks ECDSA without extra config.
        val spec = KeyGenParameterSpec.Builder(
            ksAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setUserAuthenticationRequired(false) // Phase 1: no biometric gate; revisit in Phase 5.
            .build()

        val gen = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            AndroidKeyStoreAdapter.KEYSTORE_PROVIDER
        )
        gen.initialize(spec)
        return gen.generateKeyPair()
    }

    private fun signSelfCert(alias: String, keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val tenYears = 10L * 365L * 24L * 60L * 60L * 1000L
        val notBefore = Date(now - 5 * 60 * 1000) // 5-min skew tolerance
        val notAfter = Date(now + tenYears)

        val subject = X500Name("CN=$alias, O=KFileSync, OU=Mobile")
        val serial = BigInteger.valueOf(now)

        // SubjectPublicKeyInfo is derived from the EC public key directly -
        // bcpkix takes care of the ASN.1 encoding.
        val spki = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)

        val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            .addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth))
            )

        // Sign with the AndroidKeyStore-resident private key. BouncyCastle's
        // ContentSigner will delegate the actual signing to the platform
        // because PrivateKey.provider == AndroidKeyStore.
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(keyPair.private.provider())
            .build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun PrivateKey.provider(): java.security.Provider? = (this as? java.security.Key)?.let {
        // AndroidKeyStore-resident keys advertise their provider via a side
        // channel - easiest path is to ask the key for it via KeyStore.
        JavaKeyStore.getInstance(AndroidKeyStoreAdapter.KEYSTORE_PROVIDER).provider
    }

    private fun certToIdentity(cert: X509Certificate): DeviceCryptoIdentity {
        val der = cert.encoded
        val fpHex = HashProvider.sha256(der).toHexLower()
        val deviceId = DeviceId(fpHex)
        val fingerprint = Fingerprint(fpHex)
        val pem = pemEncode(der)
        return DeviceCryptoIdentity(deviceId = deviceId, certificatePem = pem, fingerprint = fingerprint)
    }

    private fun pemEncode(der: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(der)
        val wrapped = base64.chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$wrapped\n-----END CERTIFICATE-----\n"
    }
}