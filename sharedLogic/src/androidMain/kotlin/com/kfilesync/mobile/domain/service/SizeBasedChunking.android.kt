package com.kfilesync.mobile.domain.service

/** Production Android implementation: delegates to kfilesync-core's UniFFI binding. */
actual class SizeBasedChunking actual constructor() : ChunkingStrategy {
    actual override fun computeChunkSize(fileSize: Long): Int =
        uniffi.kfilesync_core.computeChunkSize(fileSize.toULong()).toInt()
}