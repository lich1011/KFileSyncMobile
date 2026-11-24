package com.kfilesync.mobile.domain.service

/**
 * iOS actual for [PairingStateMachine], implementing KFileSyncCore's exact
 * dual-PIN state-machine semantics (`trust/pairing_state.rs`).
 */
actual class PairingStateMachine actual constructor() {

    actual fun startOutbound(
        requestId: String,
        nonce: String,
        targetDeviceId: String,
        fromDeviceId: String,
        fromAlias: String,
        fromPlatform: String,
        fromFingerprintHex: String,
        ourPin: String,
        maxAttempts: Int,
        nowMs: Long,
        pinTtlMs: Long
    ): PairingSessionState = PairingSessionState(
        requestId = requestId,
        targetDeviceId = targetDeviceId,
        ourPin = ourPin,
        expiresAtMs = nowMs + pinTtlMs,
        attempts = 0,
        maxAttempts = maxAttempts
    )

    actual fun receiveIncoming(
        requestId: String,
        fromDeviceId: String,
        ourPin: String,
        maxAttempts: Int,
        nowMs: Long,
        pinTtlMs: Long
    ): PairingSessionState = PairingSessionState(
        requestId = requestId,
        targetDeviceId = fromDeviceId,
        ourPin = ourPin,
        expiresAtMs = nowMs + pinTtlMs,
        attempts = 0,
        maxAttempts = maxAttempts
    )

    actual fun verifyPeerPin(
        session: PairingSessionState,
        providedPin: String,
        nowMs: Long
    ): Pair<PairingSessionState, PinVerdict> {
        // Expiry checked before touching attempts (matches Core pairing_state.rs:238).
        if (nowMs > session.expiresAtMs) {
            return session to PinVerdict.Expired
        }
        if (session.attempts >= session.maxAttempts) {
            return session to PinVerdict.MaxAttemptsExceeded
        }
        val nextSession = session.copy(attempts = session.attempts + 1)
        val verdict = if (constantTimeEquals(providedPin, session.ourPin)) {
            PinVerdict.Accepted
        } else {
            PinVerdict.Wrong
        }
        return nextSession to verdict
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
