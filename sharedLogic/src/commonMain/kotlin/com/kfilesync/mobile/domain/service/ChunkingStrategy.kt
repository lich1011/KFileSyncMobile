package com.kfilesync.mobile.domain.service

/** Strategy interface for splitting files into transfer chunks. */
interface ChunkingStrategy {
    /** Returns the chunk size in bytes for a given file size. 0 = no chunking. */
    fun computeChunkSize(fileSize: Long): Int
}

/** Size-based chunk strategy from design doc §6.5.2. */
class SizeBasedChunking : ChunkingStrategy {
    override fun computeChunkSize(fileSize: Long): Int = when {
        fileSize <= 131_072L        -> 0           // < 128 KiB: no chunking
        fileSize <= 268_435_456L    -> 131_072     // // 128 KiB - 256 MiB
        fileSize <= 1_073_741_824L   -> 1_048_576   // // 256 MiB - 1 GiB
        fileSize <= 17_179_869_184L  -> 4_194_304   // // 1 GiB - 16 GiB
        else                        -> 16_777_216  // // > 16 GiB
    }
}