package com.kfilesync.mobile.domain.service

/** Strategy interface for splitting files into transfer chunks. */
interface ChunkingStrategy {
    /** Returns the chunk size in bytes for a given file size. 0 = no chunking. */
    fun computeChunkSize(fileSize: Long): Int
}

/** Size-based chunk strategy from design doc §6.5.2. */
expect class SizeBasedChunking() : ChunkingStrategy {
    override fun computeChunkSize(fileSize: Long): Int 
}