package com.kfilesync.mobile.domain.service

/**
 * iOS implementation: hand-written formula, kept temporarily because this
 * machine has no full Xcode to build kfilesync-core's iOS UniFFI/XCFramework
 * artifact. Must stay in sync with the design-doc §6.5.2 tiers (and with
 * kfilesync-core's own compute_chunk_size) until iOS migrates too.
 */
actual class SizeBasedChunking actual constructor() : ChunkingStrategy {
    actual override fun computeChunkSize(fileSize: Long): Int = when {
        fileSize <= 131_072L         -> 0           // < 128 KiB: no chunking
        fileSize <= 268_435_456L     -> 131_072     // 128 KiB - 256 MiB
        fileSize <= 1_073_741_824L   -> 1_048_576   // 256 MiB - 1 GiB
        fileSize <= 17_179_869_184L  -> 4_194_304   // 1 GiB - 16 GiB
        else                         -> 16_777_216  // > 16 GiB
    }
}