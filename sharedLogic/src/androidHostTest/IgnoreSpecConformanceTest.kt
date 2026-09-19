package com.kfilesync.mobile.conformance

import kotlinx.serialization.Serializable
import uniffi.kfilesync_core.IgnoreSpec
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class IgnoreFixture(
    val name: String,
    val description: String,
    val cases: List<IgnoreCase>
)

@Serializable
private data class IgnoreCase(
    val name: String,
    val is_mobile: Boolean,
    val syncignore_content: String? = null,
    val extra_user_rules: List<String>,
    val checks: List<IgnoreCheck>
)

@Serializable
private data class IgnoreCheck(
    val path: String,
    val is_directory: Boolean,
    val expected_ignored: Boolean
)

/**
 * Conformance runner (1.3): mirrors `run_ignore_spec` in
 * `rust-runner/src/main.rs` against the UniFFI-exported `IgnoreSpec`
 * object (built once per case, shared root is irrelevant to matching so a
 * fixed placeholder is used just like the Rust runner does).
 */
class IgnoreSpecConformanceTest {

    @Test
    fun matching_agrees_with_rust() {
        val fixture: IgnoreFixture = fixture("ignore_spec/matching.json")

        for (case in fixture.cases) {
            val spec = IgnoreSpec.build(
                shareRoot = "/share-root",
                syncignoreContent = case.syncignore_content,
                extraUserRules = case.extra_user_rules,
                isMobile = case.is_mobile
            )
            for (check in case.checks) {
                assertEquals(
                    check.expected_ignored,
                    spec.isIgnored(check.path, check.is_directory),
                    "case=${case.name} path=${check.path}"
                )
            }
        }
    }
}