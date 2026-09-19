package com.kfilesync.mobile.conformance

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.VersionVector
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class VvAncestorFixture(
    val name: String,
    val cases: List<VvAncestorCase>
)

@Serializable
private data class VvAncestorCase(
    val name: String,
    val a: Map<String, Long>,
    val b: Map<String, Long>,
    val expected_a_ancestor_of_b: Boolean,
    val expected_b_ancestor_of_a: Boolean,
    val expected_conflict: Boolean
)

@Serializable
private data class VvMergeFixture(
    val name: String,
    val cases: List<VvMergeCase>
)

@Serializable
private data class VvMergeCase(
    val name: String,
    val a: Map<String, Long>,
    val b: Map<String, Long>,
    val expected_merged: Map<String, Long>
)

/**
 * Conformance runner (1.3); mirrors `run_vv_ancestor` / `run_vv_merge` in
 * `kfilesync-conformance/rust-runner/src/main.rs` against the hand-written
 * `VersionVector` in `domain/model/FileEntry.kt`. Not FFI-backed -
 * `VersionVector` only crosses the UniFFI boundary as a bare
 * `Map<String, ULong>` with no exported methods (see ADR-0015), so this
 * proves the hand-written Kotlin logic against the same fixtures Rust's
 * own `VersionVector` methods are checked against, rather than testing the
 * FFI boundary itself. Lives in androidHostTest (not commonTest) purely
 * because reading the fixture JSON needs `java.io.File` - see Fixtures.kt.
 */
class VersionVectorConformanceTest {

    @Test
    fun ancestor_basic_matches_rust() {
        val fixture: VvAncestorFixture = fixture("version_vector/ancestor_basic.json")

        for (case in fixture.cases) {
            val a = case.a.toVersionVector()
            val b = case.b.toVersionVector()
            val context = "case=${case.name}"

            assertEquals(case.expected_a_ancestor_of_b, a.isAncestorOf(b), "$context: a.isAncestorOf(b)")
            assertEquals(case.expected_b_ancestor_of_a, b.isAncestorOf(a), "$context: b.isAncestorOf(a)")
            assertEquals(case.expected_conflict, a.conflictsWith(b), "$context: a.conflictsWith(b)")
        }
    }

    @Test
    fun merge_concurrent_matches_rust() {
        val fixture: VvMergeFixture = fixture("version_vector/merge_concurrent.json")

        for (case in fixture.cases) {
            val a = case.a.toVersionVector()
            val b = case.b.toVersionVector()
            val merged = a.merge(b)

            for ((device, expectedCount) in case.expected_merged) {
                assertEquals(
                    expectedCount,
                    merged.entries[DeviceId(device)] ?: 0L,
                    "case=${case.name}: merged[$device]"
                )
            }
        }
    }
}

private fun Map<String, Long>.toVersionVector(): VersionVector =
    VersionVector(entries = mapKeys { (device, _) -> DeviceId(device) })