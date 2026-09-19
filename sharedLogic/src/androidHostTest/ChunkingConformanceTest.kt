package com.kfilesync.mobile.conformance

import kotlinx.serialization.Serializable
import uniffi.kfilesync_core.computeChunkSize
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class ChunkingFixture(
    val name: String,
    val description: String,
    val cases: List<ChunkingCase>
)

@Serializable
private data class ChunkingCase(
    val file_size: Long,
    val expected_chunk_size: Long
)

/**
 * Conformance runner (1.3): drives kfilesync-core's UniFFI-exported
 * `compute_chunk_size` with the exact same fixture Rust's own runner uses
 * (`run_chunking` in `rust-runner/src/main.rs`), proving the mobile FFI
 * boundary and the desktop core agree bit-for-bit.
 */
class ChunkingConformanceTest {

    @Test
    fun threshold_boundaries_match_rust() {
        val fixture: ChunkingFixture = fixture("chunking/threshold_boundaries.json")

        for (case in fixture.cases) {
            val actual = computeChunkSize(case.file_size.toULong())
            assertEquals(
                case.expected_chunk_size.toUInt(),
                actual,
                "file_size=${case.file_size}"
            )
        }
    }
}