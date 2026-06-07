package com.kfilesync.mobile.infrastructure.crypto

/**
 * Streaming SHA-256 hasher (T2.2 / T2.3).
 *
 * The chunk loop on the sender side reads one chunk at a time from disk;
 * we feed each chunk to both a [Blake3Hasher] (for the chunk-level fingerprint
 * that goes into the manifest) and a streaming SHA-256 (for the whole-file
 * hash that goes into the manifest's `sha256` field).
 *
 * Symmetric on the receiver side: every accepted chunk is fed into a per-file
 * SHA-256 stream so we can compare against the manifest's `sha256` at
 * file-completion time and trigger the atomic move.
 *
 * The platform `actual` wraps `MessageDigest` on Android and a chained
 * `CC_SHA256_Init`/`Update`/`Final` on iOS – both keep working state opaque
 * inside the digest object, so we don't copy chunks twice.
 */
expect class StreamingSha256() {

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size)

    fun finalize(): ByteArray

    fun reset()
}

fun StreamingSha256.hexLower(): String = finalize().toHexLower()