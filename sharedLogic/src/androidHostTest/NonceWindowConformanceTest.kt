package com.kfilesync.mobile.conformance

import com.kfilesync.mobile.domain.service.NonceWindow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class NonceFixture(
    val cases: List<NonceCase>
)

@Serializable
private data class NonceCase(
    val name: String,
    val window_size_ms: Long,
    val clock_skew_ms: Long,
    val steps: List<NonceStep>
)

@Serializable
private data class NonceStep(
    val device_id: String,
    val timestamp_ms: Long,
    val nonce: String,
    val now_ms: Long,
    val expected_verdict: String
)

private fun expectedDetailedVerdict(name: String): NonceWindow.DetailedVerdict = when (name) {
    "fresh" -> NonceWindow.DetailedVerdict.FRESH
    "stale_timestamp" -> NonceWindow.DetailedVerdict.STALE_TIMESTAMP
    "future_timestamp" -> NonceWindow.DetailedVerdict.FUTURE_TIMESTAMP
    "replay" -> NonceWindow.DetailedVerdict.REPLAY
    "blank_nonce" -> NonceWindow.DetailedVerdict.BLANK_NONCE
    else -> error("unknown expected_verdict '$name'")
}

/**
 * Conformance runner (1.3): mirrors `run_nonce_window` in
 * `rust-runner/src/main.rs` against `NonceWindow.verifyAndRecord`, the
 * explicit-time/granular-verdict API added specifically so this fixture
 * (kfilesync-core has no raw `verify_and_record` FFI export, only the
 * fused `FfiNonceWindowState.evaluateInbound`, covered by
 * [TrustConformanceTest]) has a Kotlin counterpart to run against. Each
 * case gets one fresh [NonceWindow], and steps within a case run in order
 * against it, exactly as the Rust runner does.
 */
class NonceWindowConformanceTest {

    @Test
    fun verify_and_record_matches_rust() = runTest {
        val fixture: NonceFixture = fixture("nonce_window/verify_and_record.json")

        for (case in fixture.cases) {
            val window = NonceWindow()

            for ((i, step) in case.steps.withIndex()) {
                val actual = window.verifyAndRecord(
                    deviceId = step.device_id,
                    timestampMs = step.timestamp_ms,
                    nonce = step.nonce,
                    nowMs = step.now_ms,
                    windowSizeMs = case.window_size_ms,
                    clockSkewMs = case.clock_skew_ms
                )
                assertEquals(
                    expectedDetailedVerdict(step.expected_verdict),
                    actual,
                    "case=${case.name} step=$i"
                )
            }
        }
    }
}