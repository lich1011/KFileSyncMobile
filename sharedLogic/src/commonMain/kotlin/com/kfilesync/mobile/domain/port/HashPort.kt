package com.kfilesync.mobile.domain.port

/**
 * Domain port for cryptographic hash operations (design doc §6.3).
 *
 * Decouples domain services ([com.kfilesync.mobile.domain.service.Indexer])
 * from the concrete [com.kfilesync.mobile.infrastructure.crypto] implementations,
 * restoring the hexagonal architecture invariant that domain code has zero
 * infrastructure imports.
 *
 * The infrastructure layer provides [DefaultHashPort] which wraps
 * [Blake3Hasher] and [StreamingSha256].
 */
interface HashPort {
    /** Create a new streaming BLAKE3 hasher. */
    fun newBlake3(): StreamingHasher

    /** Create a new streaming SHA-256 hasher. */
    fun newSha256(): StreamingHasher

    /** Convert a byte array to lowercase hex string. */
    fun toHexLower(bytes: ByteArray): String
}

/**
 * Streaming hash abstraction used by [HashPort].
 *
 * Implementations are NOT thread-safe; callers must use one instance
 * per coroutine (which the Indexer already does).
 */
interface StreamingHasher {
    fun update(data: ByteArray, offset: Int, length: Int)

    /** Finalize and return the raw hash bytes. */
    fun finalizeHash(): ByteArray

    /** Finalize and return the hash as a lowercase hex string. */
    fun hexLower(): String

    /** Reset the hasher for reuse (e.g. per-chunk BLAKE3). */
    fun reset()
}
