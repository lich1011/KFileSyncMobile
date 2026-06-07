package com.kfilesync.mobile.infrastructure.crypto

/**
 * Cross-platform cryptographically-secure random byte generator.
 *
 * Replaces ad-hoc uses of 'kotlin.random.Random' for PIN / nonce / session-id
 * generation, which is NOT cryptographically secure on either platform
 * (Issues #47, #48, #58 from the security review).
 *
 * - androidMain -> `java.security.SecureRandom`
 * - iosMain     -> `SecRandomCopyBytes` (Security.framework)
 *
 * Both implementations source entropy from the OS CSPRNG.
 */
expect object SecureRng {
    /** Fill [out] with cryptographically secure random bytes. */
    fun nextBytes(out: ByteArray)
}

/** Convenience: return a fresh byte array of the given length filled with CSPRNG bytes. */
fun SecureRng.nextBytes(length: Int): ByteArray {
    val buf = ByteArray(length)
    nextBytes(buf)
    return buf
}

/** Lowercase hex of [length] random bytes. Useful for nonces / session ids. */
fun SecureRng.nextHex(length: Int): String = nextBytes(length).toHexLower()

/**
 * Generate a uniformly-distributed integer in `[0, bound)` using rejection
 * sampling so the result has no modulo bias. For a 6-digit PIN, callers
 * pass `bound = 1_000_000`.
 */
fun SecureRng.nextIntBelow(bound: Int): Int {
    require(bound > 0) { "bound must be > 0" }
    // Pick the smallest power of two >= bound, then reject samples >= bound.
    val mask = Int.MAX_VALUE
    while (true) {
        val raw = nextBytes(4)
        val candidate = ((raw[0].toInt() and 0xFF) shl 24) or
                ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF)
        val nonNeg = candidate and mask
        if (nonNeg < (Int.MAX_VALUE - (Int.MAX_VALUE % bound))) {
            return nonNeg % bound
        }
        // else: tail bias zone - resample.
    }
}