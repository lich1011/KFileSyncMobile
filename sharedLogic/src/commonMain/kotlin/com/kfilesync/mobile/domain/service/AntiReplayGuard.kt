package com.kfilesync.mobile.domain.service

/** A currently-paired peer, as needed to evaluate anti-replay trust decisions. */
data class PairedDevice(val deviceId: String, val certFingerprintHex: String)

/** Outcome of evaluating an inbound request's anti-replay / trust headers. */
sealed class TrustDecision {
    /** Request is from a paired device - allow. */
    data class Allow(val peerDeviceId: String, val peerFingerprint: String) : TrustDecision()

    /** Request is from an unpaired device, but the route allows pairing. */
    data object AllowUnpairedForPairing : TrustDecision()

    /** Request is rejected. */
    data class Reject(val httpStatus: Int, val errorCode: String, val reason: String) : TrustDecision()
}

/**
 * Anti-replay + pairing-trust gate for inbound HTTP requests (design doc §7.3,
 * T5.3 hardening / issue #11).
 *
 * Android: delegates to kfilesync-core's UniFFI `FfiNonceWindowState.evaluateInbound`,
 * which folds together three checks that used to be hand-written separately in
 * `HttpServer.kt`: (1) the asserted `X-Device-Id` must be a currently-paired
 * peer (unless the route is a pairing-bootstrap route), (2) an optional
 * `X-Fingerprint` header must match the paired peer's cert, and (3) the
 * `(timestamp, nonce)` pair must be fresh and not a replay.
 *
 * iOS: temporarily keeps an equivalent hand-written implementation
 * (`AntiReplayGuard.ios.kt`, built on [NonceWindow]) until iOS gets a UniFFI
 * binding of its own.
 */
expect class AntiReplayGuard() {
    suspend fun evaluate(
        route: String,
        deviceIdHeader: String?,
        timestampMsHeader: Long?,
        nonceHeader: String?,
        fingerprintHeader: String?,
        pairedDevices: List<PairedDevice>,
        nowMs: Long,
        windowSizeMs: Long,
        clockSkewMs: Long
    ): TrustDecision
}