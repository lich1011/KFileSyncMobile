package com.kfilesync.mobile.infrastructure.crypto

import com.kfilesync.mobile.domain.port.HashPort
import com.kfilesync.mobile.domain.port.StreamingHasher

/**
 * Infrastructure implementation of [HashPort].
 *
 * Wraps the existing [Blake3Hasher] and [StreamingSha256] concrete classes
 * behind the domain port interface so the domain layer stays infrastructure-free.
 */
class DefaultHashPort : HashPort {

    override fun newBlake3(): StreamingHasher = Blake3StreamingHasher()

    override fun newSha256(): StreamingHasher = Sha256StreamingHasher()

    override fun toHexLower(bytes: ByteArray): String = bytes.toHexLower()
}

private class Blake3StreamingHasher : StreamingHasher {
    private val delegate = Blake3Hasher()
    private val out = ByteArray(32)

    override fun update(data: ByteArray, offset: Int, length: Int) {
        delegate.update(data, offset, length)
    }

    override fun finalizeHash(): ByteArray {
        delegate.finalize(out)
        return out.copyOf()
    }

    override fun hexLower(): String {
        delegate.finalize(out)
        return out.toHexLower()
    }

    override fun reset() {
        delegate.reset()
    }
}

private class Sha256StreamingHasher : StreamingHasher {
    private val delegate = StreamingSha256()

    override fun update(data: ByteArray, offset: Int, length: Int) {
        delegate.update(data, offset, length)
    }

    override fun finalizeHash(): ByteArray = delegate.finalize()

    override fun hexLower(): String = delegate.hexLower()

    override fun reset() {
        // StreamingSha256 does not support reset; create a fresh instance
        // would require reconstructing. For the Indexer's use case this is
        // fine because SHA-256 is used once per file, not reset per chunk.
        throw UnsupportedOperationException("SHA-256 streaming hasher does not support reset")
    }
}
