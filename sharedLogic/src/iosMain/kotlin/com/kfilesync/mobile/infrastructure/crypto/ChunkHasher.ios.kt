package com.kfilesync.mobile.infrastructure.crypto

/**
 * iOS keeps the hand-written Kotlin BLAKE3 implementation ([blake3]) until
 * the iOS UniFFI toolchain is wired up (see the Android `actual`, which
 * delegates to `uniffi.kfilesync_core.hashChunk`/`verifyChunk`).
 */
actual object ChunkHasher {
    actual fun hash(data: ByteArray): String = HashProvider.blake3(data).toHexLower()

    actual fun verify(data: ByteArray, expectedHex: String): Boolean = hash(data) == expectedHex
}