package com.kfilesync.mobile.platform.tls

import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import io.github.aakira.napier.Napier
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Custom [X509ExtendedTrustManager] that pins peer TLS certificates by
 * SHA-256 fingerprint against the set of paired devices the app knows
 * about (T1.4, Android path).
 *
 * Rationale: we don't use a public CA. Every device mints its own self-
 * signed certificate at first launch (T1.1). The pairing handshake exchanges
 * + persists those certs ('devices.certificate_pem'), so post-pairing we
 * already know the legitimate fingerprint set. A peer is trusted iff its
 * SHA-256 cert digest is in that set.
 *
 * Bootstrap window: before any device has been paired the pinned set is
 * empty. We then accept the very first handshake - pairing records the
 * peer's cert, and from then on the trust manager is strict.
 */
class PinningTrustManager(
    private val deviceRepository: DeviceRepository
) : X509ExtendedTrustManager() {

    private fun pinnedFingerprints(): Set<String> = runBlocking {
        deviceRepository.findPaired()
            .map { it.state }
            .filterIsInstance<com.kfilesync.mobile.domain.model.DeviceState.Paired>()
            .map { paired -> fingerprintOf(paired.certificatePem) }
            .toSet()
    }

    private fun fingerprintOf(pem: String): String {
        val der = pemToDer(pem)
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.toHexLower()
    }

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.lines()
            .filterNot { it.startsWith("-----BEGIN") || it.startsWith("-----END") }
            .joinToString("")
            .trim()
        return java.util.Base64.getDecoder().decode(body)
    }

    // ---- TrustManager methods ----

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        checkPinned(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        checkPinned(chain)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        checkPinned(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        checkPinned(chain)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        checkPinned(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        checkPinned(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun checkPinned(chain: Array<X509Certificate>) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty cert chain")
        val pinned = pinnedFingerprints()

        if (pinned.isEmpty()) {
            // Pre-pairing bootstrap window - see KDoc on the class.
            Napier.w("PinningTrustManager: no pinned fingerprints yet; accepting bootstrap handshake")
            return
        }

        val leafDigest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).toHexLower()
        if (leafDigest !in pinned) {
            throw CertificateException(
                "peer cert fingerprint $leafDigest not in pinned set (size=${pinned.size})"
            )
        }
    }
}