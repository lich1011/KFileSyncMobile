package com.kfilesync.mobile.platform.tls

import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import com.kfilesync.mobile.infrastructure.network.PinnedTrustSnapshot
import io.github.aakira.napier.Napier
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
 * Hardened (issues #7, #8, #9):
 * - No more `runBlocking` on the TLS thread. The pinned set lives in
 * [PinnedTrustSnapshot], an atomic in-memory cache that's refreshed
 * by an event-bus subscriber on every PairingCompleted / TrustRevoked.
 * - Bootstrap window is gated on a persisted "ever paired" flag. The
 * window only opens on a fresh install (or a wipe that clears the
 * `config` row). Once the user has paired even once, an empty pinned
 * set means "we revoked everyone" - NOT "trust the next peer who calls".
 *
 * Bootstrap window behaviour:
 * - Fresh install, no devices, no flag set => accept first handshake
 * (this is how pairing itself can happen).
 * - At least one prior pairing (flag set) => strict; empty pinned set
 * means we revoked every device and we MUST refuse.
 *
 * Rationale: we don't use a public CA. Every device mints its own self-
 * signed certificate at first launch (T1.1). The pairing handshake
 * exchanges + persists those certs, so post-pairing the legitimate
 * fingerprint set is known.
 */
class PinningTrustManager(
    private val pinned: PinnedTrustSnapshot
) : X509ExtendedTrustManager() {

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
        val pinnedSet = pinned.pinnedFingerprints()
        val everPaired = pinned.hasEverPaired()
        if (pinnedSet.isEmpty()) {
            if (everPaired) {
                throw CertificateException(
                    "trust state inconsistent: pinned set is empty but device has paired before. " +
                            "Refusing handshake."
                )
            }
            Napier.w("PinningTrustManager: bootstrap window active (no prior pairings); accepting first handshake")
            return
        }

        val leafDigest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).toHexLower()
        if (leafDigest !in pinnedSet) {
            throw CertificateException(
                "peer cert fingerprint $leafDigest not in pinned set (size=${pinnedSet.size})"
            )
        }
    }
}