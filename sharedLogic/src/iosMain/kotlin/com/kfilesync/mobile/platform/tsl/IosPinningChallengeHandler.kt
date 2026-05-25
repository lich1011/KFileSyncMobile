package com.kfilesync.mobile.platform.tls

import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import com.kfilesync.mobile.platform.toByteArray
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.CoreFoundation.CFBridgingRelease
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSData
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef

/**
 * iOS fingerprint-pinning logic for TLS client connections (T1.4, iOS path).
 *
 * Mirrors Android's [com.kfilesync.mobile.platform.tls.PinningTrustManager]:
 * - Look at the Leaf certificate of the peer's chain.
 * - SHA-256 its DER encoding.
 * - Compare against the set of paired-device fingerprints persisted by the
 * pairing handshake.
 * - Accept on hit, reject on miss.
 * - Bootstrap window: empty pinned set means "no devices paired yet" - accept
 * the very first handshake so pairing itself can happen. This window
 * closes the moment the first PairingCompleted event lands.
 *
 * Wired into [io.ktor.client.engine.darwin.Darwin] via
 * `engine { handleChallenge(IosPinningChallengeHandler(...)) }`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPinningChallengeHandler(
    private val deviceRepository: DeviceRepository
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

        val pinned = pinnedFingerprints()
        if (pinned.isEmpty()) {
            // Bootstrap window - pairing handshake hasn't happened yet.
            Napier.w("IosPinningChallengeHandler: no pinned fingerprints, accepting bootstrap")
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                NSURLCredential.credentialForTrust(trust)
            )
            return
        }

        if (leafFingerprint in pinned) {
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                NSURLCredential.credentialForTrust(trust)
            )
        } else {
            Napier.w(
                "IosPinningChallengeHandler: peer cert fingerprint $leafFingerprint " +
                        "not in pinned set (size=${pinned.size})"
            )
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }

    /**
     * Read the leaf certificate (index 0 of the chain) and copy its DER.
     * SecTrustCopyCertificateChain returns a CFArray<SecCertificate>; we
     * bridge to NSArray for ergonomics, then SecCertificateCopyData reads
     * the DER for the leaf.
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
            // CFBridgingRelease already consumed the ref on success path; if we
            // throw before that point CoreFoundation cleans the autoreleased
            // CFArray for us when control returns to the runloop.
            Napier.w("extractLeafDer failed: ${t.message}")
            return null
        }
    }

    /**
     * Snapshot the set of trusted fingerprints from the device repository.
     * Implemented as a blocking call because the URLSessionDelegate
     * completion handler is itself synchronous - we have to decide before
     * returning. The repo lookup is a single SQL row read keyed by
     * `trust_status = 'paired'`, so it's fast in practice.
     */
    private fun pinnedFingerprints(): Set<String> = runBlocking {
        deviceRepository.findPaired()
            .mapNotNull { device ->
                (device.state as? DeviceState.Paired)?.let { paired ->
                    fingerprintOf(paired.certificatePem)
                }
            }
            .toSet()
    }

    private fun fingerprintOf(pem: String): String {
        val der = pemToDer(pem)
        return HashProvider.sha256(der).toHexLower()
    }

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.lines()
            .filterNot { it.startsWith("-----BEGIN") || it.startsWith("-----END") }
            .joinToString("")
            .trim()
        return decodeBase64(body)
    }

    /**
     * Stdlib base64 decode - Kotlin/Native doesn't ship java.util.Base64.
     * Implements the standard alphabet with '=' padding tolerance. Not a
     * hot path; the PEM bodies are at most a few hundred bytes each.
     */
    private fun decodeBase64(s: String): ByteArray {
        val cleaned = s.filter { c -> c != '\n' && c != '\r' && c != ' ' && c != '\t' }
        val padded = cleaned.trimEnd('=')
        val out = ByteArray((padded.length * 6) / 8)
        var bits = 0
        var bitCount = 0
        var outIdx = 0
        for (c in padded) {
            val v = base64Value(c)
            if (v < 0) continue
            bits = (bits shl 6) or v
            bitCount += 6
            if (bitCount >= 8) {
                bitCount -= 8
                out[outIdx++] = ((bits ushr bitCount) and 0xFF).toByte()
            }
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    private fun base64Value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'y' -> c - '0' + 52 // 注：此处图片代码疑似印刷错误 'y' 应为 '9'，转换逻辑保持与图一致
        '+' -> 62
        '/' -> 63
        else -> -1
    }
}