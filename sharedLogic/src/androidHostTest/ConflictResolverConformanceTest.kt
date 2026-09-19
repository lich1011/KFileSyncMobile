package com.kfilesync.mobile.conformance

import kotlinx.serialization.Serializable
import uniffi.kfilesync_core.ConflictResolution
import uniffi.kfilesync_core.EntryType
import uniffi.kfilesync_core.FileEntry
import uniffi.kfilesync_core.applyResolution
import uniffi.kfilesync_core.conflictCopyName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

@Serializable
private data class CopyNameFixture(
    val cases: List<CopyNameCase>
)

@Serializable
private data class CopyNameCase(
    val name: String,
    val original_path: String,
    val losing_device_id: String,
    val conflict_at_ms: Long,
    val expected_name: String
)

/**
 * Conformance runner (1.3): mirrors `run_copy_name` /
 * `run_apply_resolution` in `rust-runner/src/main.rs` against the
 * UniFFI-exported `conflict_copy_name` / `apply_resolution`.
 */
class ConflictResolverConformanceTest {

    @Test
    fun copy_name_matches_rust() {
        val fixture: CopyNameFixture = fixture("conflict_resolver/copy_name.json")

        for (case in fixture.cases) {
            assertEquals(
                case.expected_name,
                conflictCopyName(case.original_path, case.losing_device_id, case.conflict_at_ms),
                "case=${case.name}"
            )
        }
    }

    @Test
    fun apply_resolution_matches_rust() {
        val fixture: ApplyResolutionFixture = fixture("conflict_resolver/apply_resolution.json")

        for (case in fixture.cases) {
            val local = case.local.toFileEntry()
            val remote = case.remote.toFileEntry()
            val resolution = when (case.resolution) {
                "keep_local" -> ConflictResolution.KEEP_LOCAL
                "keep_remote" -> ConflictResolution.KEEP_REMOTE
                "keep_both" -> ConflictResolution.KEEP_BOTH
                else -> fail("unknown resolution '${case.resolution}' in case=${case.name}")
            }
            val outcome = applyResolution(local, remote, resolution, case.me, case.now_ms)

            assertEntryMatches(case.expected_primary, outcome.primary, "case=${case.name} primary")

            if (case.expected_conflict_copy == null) {
                assertNull(outcome.conflictCopy, "case=${case.name}: expected no conflict_copy")
            } else {
                val actualCopy = assertNotNull(outcome.conflictCopy, "case=${case.name}: expected a conflict_copy")
                assertEntryMatches(case.expected_conflict_copy, actualCopy, "case=${case.name} conflict_copy")
            }
        }
    }
}

@Serializable
private data class ApplyResolutionFixture(
    val cases: List<ApplyResolutionCase>
)

@Serializable
private data class ApplyResolutionCase(
    val name: String,
    val local: EntryFixture,
    val remote: EntryFixture,
    val resolution: String,
    val me: String,
    val now_ms: Long,
    val expected_primary: EntryFixture,
    val expected_conflict_copy: EntryFixture?
)

/** Mirrors Rust's `EntryFixture`: only the fields `apply_resolution` reads/produces. */
@Serializable
private data class EntryFixture(
    val path: String,
    val modified_by: String,
    val modified_at_ms: Long,
    val version_vector: Map<String, ULong>,
    val deleted: Boolean,
    val deleted_at_ms: Long? = null
)

/** Mirrors `EntryFixture::to_entry`; irrelevant fields get the same placeholders Rust uses. */
private fun EntryFixture.toFileEntry(): FileEntry = FileEntry(
    shareId = "share-1",
    path = path,
    entryType = EntryType.FILE,
    size = 0UL,
    modifiedAtMs = modified_at_ms,
    modifiedBy = modified_by,
    versionVector = version_vector,
    sha256 = null,
    blocks = emptyList(),
    deleted = deleted,
    deletedAtMs = deleted_at_ms
)

/** Mirrors `EntryFixture::matches`; only the same subset of fields is compared. */
private fun assertEntryMatches(expected: EntryFixture, actual: FileEntry, context: String) {
    assertEquals(expected.path, actual.path, "$context: path")
    assertEquals(expected.modified_by, actual.modifiedBy, "$context: modified_by")
    assertEquals(expected.modified_at_ms, actual.modifiedAtMs, "$context: modified_at_ms")
    assertEquals(expected.version_vector, actual.versionVector, "$context: version_vector")
    assertEquals(expected.deleted, actual.deleted, "$context: deleted")
    assertEquals(expected.deleted_at_ms, actual.deletedAtMs, "$context: deleted_at_ms")
}