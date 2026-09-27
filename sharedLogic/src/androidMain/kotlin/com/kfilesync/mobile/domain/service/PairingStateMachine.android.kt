package com.kfilesync.mobile.domain.service

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
    ): PairingSessionState {
        val result = uniffi.kfilesync_core.startOutbound(
            requestId = requestId,
            nonce = nonce,
            targetDeviceId = targetDeviceId,
            fromDeviceId = fromDeviceId,
            fromAlias = fromAlias,
            fromPlatform = fromPlatform,
            fromFingerprintHex = fromFingerprintHex,
            ourPin = ourPin,
            maxAttempts = maxAttempts.toUInt(),
            nowMs = nowMs,
            pinTtlMs = pinTtlMs
        )
        return result.session.toDomain()
    }

    actual fun receiveIncoming(
        requestId: String,
        fromDeviceId: String,
        ourPin: String,
        maxAttempts: Int,
        nowMs: Long,
        pinTtlMs: Long
    ): PairingSessionState {
        // Only requestId/fromDeviceId feed into the resulting session (see
        // PairingStateMachine's commonMain doc comment) — the rest of this
        // DTO's fields are unused placeholders required by the type.
        val incoming = uniffi.kfilesync_core.PairRequestDto(
            requestId = requestId,
            fromDeviceId = fromDeviceId,
            fromAlias = "",
            fromPlatform = "",
            fromFingerprint = "",
            nonce = "",
            expiresAtMs = nowMs
        )
        return uniffi.kfilesync_core.receiveIncoming(
            incoming = incoming,
            ourPin = ourPin,
            maxAttempts = maxAttempts.toUInt(),
            nowMs = nowMs,
            pinTtlMs = pinTtlMs
        ).toDomain()
    }

    actual fun verifyPeerPin(
        session: PairingSessionState,
        providedPin: String,
        nowMs: Long
    ): Pair<PairingSessionState, PinVerdict> {
        uniffi.kfilesync_core.FfiPairingSession(session.toCore()).use { native ->
            val verdict = native.verifyPeerPin(providedPin, nowMs)
            return native.snapshot().toDomain() to verdict.toDomain()
        }
    }
}

private fun uniffi.kfilesync_core.PairingSession.toDomain(): PairingSessionState = PairingSessionState(
    requestId = requestId,
    targetDeviceId = targetDeviceId,
    ourPin = ourPin,
    expiresAtMs = expiresAtMs,
    attempts = attempts.toInt(),
    maxAttempts = maxAttempts.toInt()
)

private fun PairingSessionState.toCore(): uniffi.kfilesync_core.PairingSession = uniffi.kfilesync_core.PairingSession(
    requestId = requestId,
    targetDeviceId = targetDeviceId,
    ourPin = ourPin,
    expectedTheirPin = null,
    expiresAtMs = expiresAtMs,
    attempts = attempts.toUInt(),
    maxAttempts = maxAttempts.toUInt()
)

private fun uniffi.kfilesync_core.PinVerdict.toDomain(): PinVerdict = when (this) {
    uniffi.kfilesync_core.PinVerdict.ACCEPTED -> PinVerdict.Accepted
    uniffi.kfilesync_core.PinVerdict.WRONG -> PinVerdict.Wrong
    uniffi.kfilesync_core.PinVerdict.EXPIRED -> PinVerdict.Expired
    uniffi.kfilesync_core.PinVerdict.MAX_ATTEMPTS_EXCEEDED -> PinVerdict.MaxAttemptsExceeded
}