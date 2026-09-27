package com.kfilesync.mobile.domain.service

import uniffi.kfilesync_core.FfiNonceWindowState
import uniffi.kfilesync_core.FfiTrustDecision
import uniffi.kfilesync_core.PairedDeviceEntry
import uniffi.kfilesync_core.RequestMeta

/**
 * Production Android implementation: delegates to kfilesync-core's UniFFI
 * `FfiNonceWindowState`. Holds one long-lived native handle for the process
 * lifetime (mirrors the previous singleton [NonceWindow] instance's implicit
 * lifecycle) — not explicitly closed, since the app never tears this down
 * before process death.
 */
actual class AntiReplayGuard actual constructor() {
    private val native = FfiNonceWindowState()

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
        val requestMeta = RequestMeta(
            route = route,
            deviceIdHeader = deviceIdHeader,
            timestampMsHeader = timestampMsHeader,
            nonceHeader = nonceHeader,
            fingerprintHeader = fingerprintHeader
        )
        val entries = pairedDevices.map { PairedDeviceEntry(it.deviceId, it.certFingerprintHex) }
        return when (val decision = native.evaluateInbound(requestMeta, entries, nowMs, windowSizeMs, clockSkewMs)) {
            is FfiTrustDecision.Allow -> TrustDecision.Allow(decision.peerDeviceId, decision.peerFingerprint)
            is FfiTrustDecision.AllowUnpairedForPairing -> TrustDecision.AllowUnpairedForPairing
            is FfiTrustDecision.Reject -> TrustDecision.Reject(
                httpStatus = decision.httpStatus.toInt(),
                errorCode = decision.errorCode,
                reason = decision.reason
            )
        }
    }
}