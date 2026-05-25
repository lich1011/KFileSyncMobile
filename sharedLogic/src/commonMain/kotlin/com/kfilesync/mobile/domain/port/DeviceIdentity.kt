package com.kfilesync.mobile.domain.port

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.Fingerprint

/**
 * Bundle holding the cryptographic identity of the local device:
 * a self-signed X.509 cert + its SHA-256 fingerprint + the DeviceId derived
 * from the cert's DER.
 *
 * The corresponding private key is kept inside the platform's secure
 * enclave (AndroidKeyStore / iOS Keychain) and never leaves it - the only
 * way to "use" the key is via the platform's signing API. We expose just
 * the cert PEM here.
 */
data class DeviceCryptoIdentity(
    val deviceId: DeviceId,
    val certificatePem: String,
    val fingerprint: Fingerprint
)

/**
 * Driven port for the local device's cryptographic identity (T1.1).
 *
 * Lifecycle:
 * 1. `LoadOrGenerate(alias)` - call once at app boot. Either returns the
 * previously-minted identity or generates a fresh EC P-256 keypair in
 * the platform secure storage and self-signs a fresh certificate.
 * Idempotent across process restarts.
 * 2. `wipe()` - used by "reset device" / Phase 5 (TS.2) recovery paths.
 *
 * Adapters:
 * - androidMain: `AndroidDeviceIdentityProviderImpl` (AndroidKeyStore +
 * BouncyCastle bcpkix for the X.509 signing).
 * - iosMain: `IosDeviceIdentityProviderImpl` (Security.framework
 * SecKey* + SecCertificateCreateWithData).
 */
interface DeviceIdentityProvider {
    /**
     * Idempotently obtain the local device's crypto identity, generating one
     * the first time it's called. [alias] is the human-readable display name
     * we want baked into the certificate's CN; it doesn't need to be stable.
     */
    suspend fun loadOrGenerate(alias: String): DeviceCryptoIdentity

    /** Wipe the stored identity (used for "reset" / test cleanup). */
    suspend fun wipe()
}