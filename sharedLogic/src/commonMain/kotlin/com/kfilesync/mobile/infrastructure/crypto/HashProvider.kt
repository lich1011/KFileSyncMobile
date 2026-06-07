package com.kfilesync.mobile.infrastructure.crypto

/**
 * Cross-platform hash provider (design doc §6.4).
 *
 * Phase 0 wired SHA-256 platform-native (MessageDigest / CommonCrypto).
 * Phase 2 (T2.2 / T2.3) adds BLAKE3 - implemented as pure Kotlin in
 * commonMain via [Blake3Hasher], so no platform actual is required.
 *
 * `sha256` stays expect/actual because the platform-native implementations
 * are FIPS-blessed + hardware-accelerated where available.
 */
expect object HashProvider {
    /** File-level checksum. Implemented on both platforms via the OS crypto stack. */
    fun sha256(data: ByteArray): ByteArray
}

/** Chunk-level fingerprint. Common code; produces interop-identical digests. */
fun HashProvider.blake3(data: ByteArray, length: Int = data.size): ByteArray {
    val hasher = Blake3Hasher()
    hasher.update(data, 0, length)
    val out = ByteArray(32)
    hasher.finalize(out)
    return out
}

/**
 * Lowercase hex encoding of a digest. Lives in commonMain so the same wire
 * format is used everywhere we render a fingerprint (DeviceId derivation,
 * pairing UI, log lines).
 */
fun ByteArray.toHexLower(): String =
    joinToString(separator = "") { byte -> ((byte.toInt() and 0xFF)).toString(16).padStart(2, '0') }