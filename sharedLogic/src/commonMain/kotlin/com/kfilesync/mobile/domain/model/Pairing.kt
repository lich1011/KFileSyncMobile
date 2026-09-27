package com.kfilesync.mobile.domain.model

import com.kfilesync.mobile.domain.DomainError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 6-digit PIN value object - guards format invariants at construction time. */
@kotlin.jvm.JvmInline
value class PairingCode(val digits: String) {
    init {
        require(digits.length == LENGTH) { "PairingCode must be $LENGTH digits, got '${digits.length}'" }
        require(digits.all(Char::isDigit)) { "PairingCode must contain digits only" }
    }

    companion object { const val LENGTH: Int = 6 }
}

/** Random nonce used to prevent pairing-replay attacks. */
@kotlin.jvm.JvmInline
value class Nonce(val value: String)

/** Session-expiry wall-clock instant. */
data class SessionExpiry(val expiresAt: Instant) {
    fun isExpired(now: Instant): Boolean = now >= expiresAt
}

/** Outgoing = we initiated, Incoming = peer initiated. */
enum class PairingDirection { Outgoing, Incoming }

/** Lifecycle of a pairing session. */
enum class PairingStatus { Pending, Succeeded, Failed, Expired, Cancelled }

/** Result of [PairingSession.applyVerdict]: the session to persist, plus the outcome. */
data class PinVerdictOutcome(val session: PairingSession, val result: Result<Unit>)

/**
 * PairingSession aggregate root (design doc §6.1.2, T1.3).
 *
 * Invariants enforced inside the aggregate:
 * - PIN is 6 digits (via [PairingCode])
 * - At most [maxAttempts] failed attempts before the session is marked Failed
 * - Session expires 5 minutes after creation (configurable for tests)
 * - Once a terminal state (Succeeded/Failed/Expired/Cancelled) is reached,
 *   no further transitions are allowed.
 *
 * 'attempts'/'maxAttempts' count upward (Sprint 5: adopted to match core's
 * `domain::PairingSession` record shape, which mobile's PIN validation now
 * delegates to via [com.kfilesync.mobile.domain.service.PairingStateMachine]
 * - see that class for why the state machine itself, not this aggregate,
 * owns the attempt-counting logic), UI code wanting "attempts remaining"
 * computes `maxAttempts - attempts`.
 *
 * Pure data + pure methods only - no IO. Persistence is a separate concern
 * handled by [com.kfilesync.mobile.domain.port.PairingRequestRepository] (T1.6).
 */
data class PairingSession(
    val sessionId: String,
    val peerDeviceId: DeviceId,
    val direction: PairingDirection,
    val pin: PairingCode,
    val nonce: Nonce,
    val peerFingerprint: Fingerprint,
    val peerAlias: String,
    val peerPlatform: DevicePlatform,
    val expiry: SessionExpiry,
    val attempts: Int = 0,
    val maxAttempts: Int = MAX_ATTEMPTS,
    val status: PairingStatus = PairingStatus.Pending,
    val createdAt: Instant
) {
    init {
        require(attempts in 0..maxAttempts) {
            "attempts out of range: $attempts (maxAttempts=$maxAttempts)"
        }
    }

    /** True if the session is still in a state where [applyVerdict] is meaningful. */
    val isOpen: Boolean get() = status == PairingStatus.Pending

    /**
     * Mark the session expired if the wall-clock now is past expiry.
     * No-op if the session is already in a terminal state.
     */
    fun expireIfNeeded(now: Instant): PairingSession =
        if (isOpen && expiry.isExpired(now)) copy(status = PairingStatus.Expired) else this

    /**
     * Cancel the session (user pressed "cancel" / app revoked the request).
     * No-op if already terminal.
     */
    fun cancel(): PairingSession =
        if (isOpen) copy(status = PairingStatus.Cancelled) else this

    /**
     * Apply a [com.kfilesync.mobile.domain.service.PinVerdict] produced by
     * [com.kfilesync.mobile.domain.service.PairingStateMachine.verifyPeerPin]
     * - the state machine already advanced `attempts`; this folds that
     * updated count plus the verdict into the aggregate's own status field.
     * The updated session (to persist regardless of outcome) is always
     * available via [PinVerdictOutcome.session]; [PinVerdictOutcome.result]
     * mirrors the old [submitPin] contract for callers that only care about
     * success/failure.
     */
    fun applyVerdict(
        verdict: com.kfilesync.mobile.domain.service.PinVerdict,
        newAttempts: Int
    ): PinVerdictOutcome {
        val remaining = maxAttempts - newAttempts
        return when (verdict) {
            com.kfilesync.mobile.domain.service.PinVerdict.Accepted -> PinVerdictOutcome(
                session = copy(attempts = newAttempts, status = PairingStatus.Succeeded),
                result = Result.success(Unit)
            )
            com.kfilesync.mobile.domain.service.PinVerdict.Wrong -> {
                val reason = if (remaining <= 0) "max attempts exceeded" else "wrong PIN; $remaining attempt(s) remaining"
                PinVerdictOutcome(
                    session = if (remaining <= 0) copy(attempts = newAttempts, status = PairingStatus.Failed)
                              else copy(attempts = newAttempts),
                    result = Result.failure(DomainError.PermissionDenied(reason))
                )
            }
            com.kfilesync.mobile.domain.service.PinVerdict.Expired -> PinVerdictOutcome(
                session = copy(status = PairingStatus.Expired),
                result = Result.failure(DomainError.InvalidStateTransition("session expired"))
            )
            com.kfilesync.mobile.domain.service.PinVerdict.MaxAttemptsExceeded -> PinVerdictOutcome(
                session = copy(status = PairingStatus.Failed),
                result = Result.failure(DomainError.PermissionDenied("max attempts exceeded"))
            )
        }
    }

    companion object {
        const val MAX_ATTEMPTS: Int = 3
        val DEFAULT_TTL: Duration = 5.minutes
    }
}