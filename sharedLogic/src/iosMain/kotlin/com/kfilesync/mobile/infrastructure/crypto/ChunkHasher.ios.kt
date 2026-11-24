package com.kfilesync.mobile.infrastructure.crypto

/**
 * iOS keeps the hand-written Kotlin BLAKE3 implementation ([blake3]) until
 * the iOS UniFFI toolchain is wired up (see the Android `actual`, which
 * delegates to `uniffi.kfilesync_core.hashChunk`/`verifyChunk`).
 */
actual object ChunkHasher {
    actual fun hash(data: ByteArray): String = HashProvider.blake3(data).toHexLower()

    actual fun verify(data: ByteArray, expectedHex: String): Boolean {
        val actual = hash(data)
        if (actual.length != expectedHex.length) return false
        var diff = 0
        for (i in actual.indices) {
            diff = diff or (actual[i].code xor expectedHex[i].code)
        }
        return diff == 0
    }
}