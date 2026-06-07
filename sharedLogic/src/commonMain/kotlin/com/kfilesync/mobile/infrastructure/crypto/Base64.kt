package com.kfilesync.mobile.infrastructure.crypto

/**
 * Common-Kotlin Base64 decoder so callers in commonMain don't need to
 * depend on 'java.util.Base64' (Android) or 'NSData' (iOS), RFC 4648 §4,
 * permissive of whitespace and missing padding.
 * Used by:
 * - [com.kfilesync.mobile.infrastructure.network.PinnedTrustSnapshot]
 * (extract cert DER for fingerprinting)
 * - [com.kfilesync.mobile.infrastructure.network.HttpServer] cross-check
 * of asserted fingerprint header against paired cert
 */
fun base64Decode(s: String): ByteArray {
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
    in '0'..'9' -> c - '0' + 52
    '+' -> 62
    '/' -> 63
    else -> -1
}