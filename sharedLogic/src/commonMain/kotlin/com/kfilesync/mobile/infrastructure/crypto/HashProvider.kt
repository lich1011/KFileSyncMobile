package com.kfilesync.mobile.infrastructure.crypto

/**
 * Cross-platform hash provider (design doc §6.4).
 *
 * The `expect` declaration lives here; each platform module provides an
 * `actual` implementation:
 *
 * - androidMain: AndroidCryptoProvider (java.security.MessageDigest +
 * optional blake3-jni native lib).
 * - iosMain:     IosCryptoProvider (CommonCrypto CC_SHA256 + Blake3 via
 * cinterop'ed C library).
 *
 * Phase 0: SHA-256 is fully wired on both platforms (it's mandatory for the
 * file-level checksum that round-trips with the desktop). BLAKE3 is still a
 * Phase 2 deliverable - the platform `actual`'s throw until that work lands.
 */
expect object HashProvider {

    /** Chunk-level fingerprint. Phase 2 (T2.3) - platform `actual`'s currently throw. */
    fun blake3(data: ByteArray): ByteArray

    /** File-level checksum. Implemented on both platforms in Phase 0. */
    fun sha256(data: ByteArray): ByteArray
}

/**
 * Lowercase hex encoding of a digest. Lives in commonMain so the same wire
 * format is used everywhere we render a fingerprint (DeviceId derivation,
 * pairing UI, log lines).
 */
fun ByteArray.toHexLower(): String =
    joinToString(separator = "") { byte -> ((byte.toInt() and 0xFF)).toString(16).padStart(2, '0') }