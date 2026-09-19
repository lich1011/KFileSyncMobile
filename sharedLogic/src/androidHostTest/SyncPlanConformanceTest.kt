package com.kfilesync.mobile.conformance

import kotlinx.serialization.Serializable
import uniffi.kfilesync_core.EntryType
import uniffi.kfilesync_core.FileEntry
import uniffi.kfilesync_core.generate
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class SyncPlanFixture(
    val cases: List<SyncPlanCase>
)

@Serializable
private data class SyncPlanCase(
    val name: String,
    val local: List<PartialSyncEntry>,
    val remote: List<PartialSyncEntry>,
    val expected_to_push: List<String>,
    val expected_to_pull: List<String>,
    val expected_conflicts: List<String>,
    val expected_unchanged: List<String>
)

@Serializable
private data class PartialSyncEntry(
    val path: String,
    val version_vector: Map<String, ULong>,
    val deleted: Boolean
)

/** Mirrors `PartialSyncEntry::to_entry` in `rust-runner/src/main.rs` exactly, placeholder for placeholder. */
private fun PartialSyncEntry.toFileEntry(): FileEntry = FileEntry(
    shareId = "share-1",
    path = path,
    entryType = EntryType.FILE,
    size = 0UL,
    modifiedAtMs = 0L,
    modifiedBy = "x",
    versionVector = version_vector,
    sha256 = null,
    blocks = emptyList(),
    deleted = deleted,
    deletedAtMs = if (deleted) 0L else null
)

/**
 * Conformance runner (1.3): mirrors `run_sync_plan` in
 * `rust-runner/src/main.rs` against the UniFFI-exported `generate` (sync
 * plan generator).
 */
class SyncPlanConformanceTest {

    @Test
    fun tombstone_propagation_matches_rust() {
        val fixture: SyncPlanFixture = fixture("sync_plan/tombstone_propagation.json")

        for (case in fixture.cases) {
            val local = case.local.map { it.toFileEntry() }
            val remote = case.remote.map { it.toFileEntry() }
            val plan = generate(local, remote)

            assertEquals(case.expected_to_push, plan.toPush.map { it.path }, "case=${case.name} push")
            assertEquals(case.expected_to_pull, plan.toPull.map { it.path }, "case=${case.name} pull")
            assertEquals(case.expected_conflicts, plan.conflicts.map { it.path }, "case=${case.name} conflicts")
            assertEquals(case.expected_unchanged, plan.unchanged.map { it.path }, "case=${case.name} unchanged")
        }
    }
}