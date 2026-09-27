package com.kfilesync.mobile.infrastructure.crypto

import uniffi.kfilesync_core.hashChunk
import uniffi.kfilesync_core.verifyChunk

actual object ChunkHasher {
    actual fun hash(data: ByteArray): String = hashChunk(data)

    actual fun verify(data: ByteArray, expectedHex: String): Boolean = verifyChunk(data, expectedHex)
}
