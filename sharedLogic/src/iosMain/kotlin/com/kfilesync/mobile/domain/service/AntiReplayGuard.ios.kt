package com.kfilesync.mobile.domain.service

/**
 * iOS implementation: hand-written fallback, kept temporarily because this
 * machine has no full Xcode to build kfilesync-core's iOS UniFFI/XCFramework
 * artifact (mirrors [ConflictResolver]'s / [SizeBasedChunking]'s iOS
 * fallbacks in this and the previous sprint).
 *
 * Reproduces the pairing-bootstrap-route check, fingerprint cross-check, and
 * nonce-window check that `HttpServer.kt` used to do inline before this
 * migration - see git history of `HttpServer.verifyAntiReplay` for the
 * pre-migration version this was extracted from.
 *
 * The bootstrap route list **must be kept in sync** with kfilesync-core's own
 * route-based pairing allowlist (used by Android's `evaluateInbound`) -
 * changing a route on either side without updating the other silently
 * diverges Android/iOS trust behavior.
 */
actual class AntiReplayGuard actual constructor() {
    private val nonceWindow = NonceWindow()

    actual suspend fun evaluate(
        route: String,
        deviceIdHeader: String?,
        timestampMsHeader: Long?,
        nonceHeader: String?,
        fingerprintHeader: String?,
        pairedDevices: List<PairedDevice>,
        nowMs: Long,
        windowSizeMs: Long,
        clockSkewMs: Long
    ): TrustDecision {
        // Rule 1: mandatory anti-replay headers
        if (deviceIdHeader.isNullOrBlank() || timestampMsHeader == null || nonceHeader.isNullOrBlank()) {
            return TrustDecision.Reject(401, "missing_anti_replay_headers", "X-Device-Id, X-Timestamp, X-Nonce required")
        }

        // Rules 2 & 3: timestamp freshness & nonce uniqueness (matches Core rule order 1 -> 2 -> 3 -> 4 -> 5)
        val verdict = nonceWindow.verifyAndRecord(
            deviceId = deviceIdHeader,
            timestampMs = timestampMsHeader,
            nonce = nonceHeader,
            nowMs = nowMs,
            windowSizeMs = windowSizeMs,
            clockSkewMs = clockSkewMs
        )
        when (verdict) {
            NonceWindow.DetailedVerdict.FRESH -> Unit
            NonceWindow.DetailedVerdict.BLANK_NONCE ->
                return TrustDecision.Reject(401, "missing_anti_replay_headers", "blank nonce")
            NonceWindow.DetailedVerdict.STALE_TIMESTAMP ->
                return TrustDecision.Reject(401, "stale_or_future_timestamp", "timestamp too old")
            NonceWindow.DetailedVerdict.FUTURE_TIMESTAMP ->
                return TrustDecision.Reject(401, "stale_or_future_timestamp", "timestamp too far in future")
            NonceWindow.DetailedVerdict.REPLAY ->
                return TrustDecision.Reject(401, "nonce_replay", "nonce replay")
        }

        // Rule 4: bootstrap route bypass vs paired-device membership (Core returns 403)
        if (route in BOOTSTRAP_ROUTES) {
            return TrustDecision.AllowUnpairedForPairing
        }

        val paired = pairedDevices.firstOrNull { it.deviceId == deviceIdHeader }
            ?: return TrustDecision.Reject(403, "unknown_peer", "X-Device-Id is not a paired peer")

        // Rule 5: optional fingerprint cross-check (constant-time, exact match, Core returns 403)
        if (!fingerprintHeader.isNullOrBlank() && !constantTimeEquals(fingerprintHeader, paired.certFingerprintHex)) {
            return TrustDecision.Reject(403, "fingerprint_mismatch", "X-Fingerprint does not match paired peer")
        }

        return TrustDecision.Allow(deviceIdHeader, paired.certFingerprintHex)
    }

    actual fun clearDevice(deviceId: String) {
        nonceWindow.clear(deviceId)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private companion object {
        val BOOTSTRAP_ROUTES = setOf(
            "/api/lansync/v1/pair/request",
            "/api/lansync/v1/pair/confirm",
            "/api/lansync/v1/register"
        )
    }
}