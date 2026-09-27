package com.kfilesync.mobile.infrastructure.crypto

/**
 * Chunk-level BLAKE3 fingerprint used by the Sprint 4 chunk transfer channel.
 *
 * `expect/actual` split (same pattern as [com.kfilesync.mobile.domain.service.ChunkingStrategy]
 * / `ConflictResolver` / `PolicyEnforcer`): Android delegates to the real
 * `kfilesync-core` UniFFI binding (`hashChunk`/`verifyChunk` in
 * `crypto/chunk_hasher.rs`), which lives outside commonMain's classpath. iOS
 * keeps the hand-written [blake3] extension until its UniFFI toolchain is
 * wired up.
 */
expect object ChunkHasher {
    /** Lowercase hex BLAKE3 digest of [data]. */
    fun hash(data: ByteArray): String

    /** Constant-time-where-possible comparison of [data]'s digest against [expectedHex]. */
    fun verify(data: ByteArray, expectedHex: String): Boolean
}