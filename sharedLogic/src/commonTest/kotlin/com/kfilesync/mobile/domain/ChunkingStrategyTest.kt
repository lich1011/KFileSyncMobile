package com.kfilesync.mobile.domain

import com.kfilesync.mobile.domain.service.SizeBasedChunking
import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkingStrategyTest {

    private val strategy = SizeBasedChunking()

    // ---------- boundary: no chunking ----------

    @Test
    fun zero_byte_file_returns_no_chunking() {
        assertEquals(0, strategy.computeChunkSize(0L))
    }

    @Test
    fun one_byte_file_returns_no_chunking() {
        assertEquals(0, strategy.computeChunkSize(1L))
    }

    @Test
    fun at_128KiB_threshold_returns_no_chunking() {
        assertEquals(0, strategy.computeChunkSize(131_072L))
    }

    // ---------- boundary: 128 KiB chunks ----------

    @Test
    fun just_above_128KiB_returns_128KiB_chunks() {
        assertEquals(131_072, strategy.computeChunkSize(131_073L))
    }

    @Test
    fun at_256MiB_boundary_returns_128KiB_chunks() {
        assertEquals(131_072, strategy.computeChunkSize(268_435_456L))
    }

    // ---------- boundary: 1 MiB chunks ----------

    @Test
    fun just_above_256MiB_returns_1MiB_chunks() {
        assertEquals(1_048_576, strategy.computeChunkSize(268_435_457L))
    }

    @Test
    fun at_1GiB_boundary_returns_1MiB_chunks() {
        assertEquals(1_048_576, strategy.computeChunkSize(1_073_741_824L))
    }

    // ---------- boundary: 4 MiB chunks ----------

    @Test
    fun just_above_1GiB_returns_4MiB_chunks() {
        assertEquals(4_194_304, strategy.computeChunkSize(1_073_741_825L))
    }

    @Test
    fun at_16GiB_boundary_returns_4MiB_chunks() {
        assertEquals(4_194_304, strategy.computeChunkSize(17_179_869_184L))
    }

    // ---------- boundary: 16 MiB chunks ----------

    @Test
    fun above_16GiB_returns_16MiB_chunks() {
        assertEquals(16_777_216, strategy.computeChunkSize(17_179_869_185L))
    }

    @Test
    fun very_large_file_returns_16MiB_chunks() {
        assertEquals(16_777_216, strategy.computeChunkSize(1_000_000_000_000L))
    }
}
