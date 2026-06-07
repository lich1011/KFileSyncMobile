package com.kfilesync.mobile.platform.tls

import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import com.kfilesync.mobile.infrastructure.network.PinnedTrustSnapshot
import com.kfilesync.mobile.platform.toByteArray
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.CFBridgingRelease
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSData
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLProtectionSpace
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef

/**
 * iOS fingerprint-pinning logic for TLS client connections (T1.4, iOS path).
 *
 * Hardened (issues #7, #8, #9):
 * - No more 'runBlocking' on the URLSession delegate thread. The pinned
 * set lives in [PinnedTrustSnapshot] (lock-free atomic cache,
 * refreshed via the event bus on pairing / revocation).
 * - Bootstrap window only opens on a fresh install. Once the user has
 * paired anyone, an empty pinned set means we revoked everyone and
 * refusing is the right call.
 * - Mirrors Android's [com.kfilesync.mobile.platform.tls.PinningTrustManager].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPinningChallengeHandler(
    private val pinned: PinnedTrustSnapshot
) {

    /**
     * Ktor's Darwin engine challenge handler signature:
     * `(session, task, challenge, completionHandler) -> Unit`.
     *
     * Returns the disposition + credential by invoking [completionHandler].
     */
    fun handle(
        challenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit
    ) {
        val protectionSpace = challenge.protectionSpace
        if (protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            // We don't speak Basic / Digest / NTLM; let URLSession handle non-TLS auth.
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }

        val trust: SecTrustRef = protectionSpace.serverTrust ?: run {
            Napier.w("IosPinningChallengeHandler: no serverTrust on challenge")
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            return
        }

        val leafCertDer = extractLeafDer(trust) ?: run {
            Napier.w("IosPinningChallengeHandler: empty cert chain")
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            return
        }

        val leafFingerprint = HashProvider.sha256(leafCertDer).toHexLower()

        val pinnedSet = pinned.pinnedFingerprints()
        val everPaired = pinned.hasEverPaired()
        if (pinnedSet.isEmpty()) {
            if (everPaired) {
                Napier.w(
                    "IosPinningChallengeHandler: trust state inconsistent " +
                            "(pinned set empty but device has paired before); refusing"
                )
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                return
            }

            // Bootstrap window - pairing handshake hasn't happened yet.
            Napier.w("IosPinningChallengeHandler: bootstrap window active; accepting first handshake")
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                NSURLCredential.credentialForTrust(trust)
            )
            return
        }

        if (leafFingerprint in pinnedSet) {
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                NSURLCredential.credentialForTrust(trust)
            )
        } else {
            Napier.w(
                "IosPinningChallengeHandler: peer cert fingerprint $leafFingerprint " +
                        "not in pinned set (size=${pinnedSet.size})"
            )
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }

    /**
     * Read the leaf certificate (index 0 of the chain) and copy its DER.
     */
    private fun extractLeafDer(trust: SecTrustRef): ByteArray? {
        val chainCf = SecTrustCopyCertificateChain(trust) ?: return null
        try {
            @Suppress("UNCHECKED_CAST")
            val chain = CFBridgingRelease(chainCf) as List<*>
            if (chain.isEmpty()) return null
            val leaf = chain.first() as? SecCertificateRef ?: return null
            val derCf = SecCertificateCopyData(leaf) ?: return null
            try {
                @Suppress("UNCHECKED_CAST")
                val nsData = CFBridgingRelease(derCf) as NSData
                return nsData.toByteArray()
            } catch (t: Throwable) {
                CFRelease(derCf)
                throw t
            }
        } catch (t: Throwable) {
            Napier.w("extractLeafDer failed: ${t.message}")
            return null
        }
    }
}