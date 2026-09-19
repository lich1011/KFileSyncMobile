package com.kfilesync.mobile.conformance

import kotlinx.serialization.Serializable
import uniffi.kfilesync_core.FfiNonceWindowState
import uniffi.kfilesync_core.FfiTrustDecision
import uniffi.kfilesync_core.PairedDeviceEntry
import uniffi.kfilesync_core.RequestMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

@Serializable
private data class TrustFixture(
    val name: String,
    val cases: List<TrustCase>
)

@Serializable
private data class TrustCase(
    val name: String,
    val window_size_ms: Long,
    val clock_skew_ms: Long,
    val paired_devices: List<PairedDeviceFixture>,
    val requests: List<TrustRequestCase>
)

@Serializable
private data class PairedDeviceFixture(
    val device_id: String,
    val cert_fingerprint_hex: String
)

@Serializable
private data class TrustRequestCase(
    val route: String,
    val device_id_header: String? = null,
    val timestamp_ms_header: Long? = null,
    val nonce_header: String? = null,
    val fingerprint_header: String? = null,
    val clock_now_ms: Long,
    val expected: ExpectedTrustOutcome
)

@Serializable
private data class ExpectedTrustOutcome(
    val kind: String,
    val peer_device_id: String? = null,
    val peer_fingerprint: String? = null,
    val http_status: Int? = null,
    val error_code: String? = null
)

/**
 * Conformance runner (1.3): mirrors `run_trust` in
 * `rust-runner/src/main.rs` against the UniFFI-exported
 * `FfiNonceWindowState.evaluateInbound`. Each case gets one fresh
 * [FfiNonceWindowState], and requests within a case run in order against
 * it needed for the replay-detection cases, exactly as the Rust runner
 * does.
 */
class TrustConformanceTest {

    @Test
    fun evaluate_inbound_matches_rust() {
        val fixture: TrustFixture = fixture("trust/evaluate_inbound.json")

        for (case in fixture.cases) {
            val pairedDevices = case.paired_devices.map { PairedDeviceEntry(it.device_id, it.cert_fingerprint_hex) }
            val state = FfiNonceWindowState()

            for ((i, req) in case.requests.withIndex()) {
                val meta = RequestMeta(
                    route = req.route,
                    deviceIdHeader = req.device_id_header,
                    timestampMsHeader = req.timestamp_ms_header,
                    nonceHeader = req.nonce_header,
                    fingerprintHeader = req.fingerprint_header
                )
                val decision = state.evaluateInbound(
                    meta,
                    pairedDevices,
                    req.clock_now_ms,
                    case.window_size_ms,
                    case.clock_skew_ms
                )
                val context = "case=${case.name} step=$i"

                when (req.expected.kind) {
                    "allow" -> {
                        val allow = decision as? FfiTrustDecision.Allow
                            ?: fail("$context: expected allow, got$decision")
                        assertEquals(req.expected.peer_device_id, allow.peerDeviceId, "$context: peer_device_id")
                        assertEquals(req.expected.peer_fingerprint, allow.peerFingerprint, "$context: peer_fingerprint")
                    }
                    "allow_unpaired" -> {
                        if (decision !is FfiTrustDecision.AllowUnpairedForPairing) {
                            fail("$context: expected allow_unpaired, got$decision")
                        }
                    }
                    "reject" -> {
                        val reject = decision as? FfiTrustDecision.Reject
                            ?: fail("$context: expected reject, got$decision")
                        assertEquals(req.expected.http_status, reject.httpStatus.toInt(), "$context: http_status")
                        assertEquals(req.expected.error_code, reject.errorCode, "$context: error_code")
                    }
                    else -> fail("$context: unknown expected.kind '${req.expected.kind}'")
                }
            }
        }
    }
}