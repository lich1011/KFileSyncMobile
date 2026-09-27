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
        if (deviceIdHeader.isNullOrBlank() || timestampMsHeader == null || nonceHeader.isNullOrBlank()) {
            return TrustDecision.Reject(401, "missing_anti_replay_headers", "X-Device-Id, X-Timestamp, X-Nonce required")
        }

        val allowUnpairedForPairing = route in BOOTSTRAP_ROUTES
        var peerFingerprint = ""

        if (!allowUnpairedForPairing) {
            val paired = pairedDevices.firstOrNull { it.deviceId == deviceIdHeader }
                ?: return TrustDecision.Reject(401, "unknown_peer", "X-Device-Id is not a paired peer")
            peerFingerprint = paired.certFingerprintHex

            if (!fingerprintHeader.isNullOrBlank() && !fingerprintHeader.equals(paired.certFingerprintHex, ignoreCase = true)) {
                return TrustDecision.Reject(401, "fingerprint_mismatch", "X-Fingerprint does not match paired peer")
            }
        }

        val verdict = nonceWindow.verifyAndRecord(
            deviceId = deviceIdHeader,
            timestampMs = timestampMsHeader,
            nonce = nonceHeader,
            nowMs = nowMs,
            windowSizeMs = windowSizeMs,
            clockSkewMs = clockSkewMs
        )

        return when (verdict) {
            NonceWindow.DetailedVerdict.FRESH ->
                if (allowUnpairedForPairing) TrustDecision.AllowUnpairedForPairing
                else TrustDecision.Allow(deviceIdHeader, peerFingerprint)
            NonceWindow.DetailedVerdict.BLANK_NONCE -> TrustDecision.Reject(401, "anti_replay", "blank nonce")
            NonceWindow.DetailedVerdict.STALE_TIMESTAMP -> TrustDecision.Reject(401, "anti_replay", "timestamp too old")
            NonceWindow.DetailedVerdict.FUTURE_TIMESTAMP -> TrustDecision.Reject(401, "anti_replay", "timestamp too far in future")
            NonceWindow.DetailedVerdict.REPLAY -> TrustDecision.Reject(401, "anti_replay", "nonce replay")
        }
    }

    private companion object {
        val BOOTSTRAP_ROUTES = setOf(
            "/api/lansync/v1/pair/request",
            "/api/lansync/v1/pair/confirm",
            "/api/lansync/v1/register"
        )
    }
}