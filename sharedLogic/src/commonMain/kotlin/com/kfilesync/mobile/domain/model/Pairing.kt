package com.kfilesync.mobile.domain.model

import com.kfilesync.mobile.domain.DomainError
import kotlin.time.Clock
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

/**
 * PairingSession aggregate root (design doc §6.1.2, T1.3).
 *
 * Invariants enforced inside the aggregate:
 * - PIN is 6 digits (via [PairingCode])
 * - At most 3 failed attempts before the session is marked Failed
 * - Session expires 5 minutes after creation (configurable for tests)
 * - Once a terminal state (Succeeded/Failed/Expired/Cancelled) is reached,
 * no further transitions are allowed.
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
    val attemptsRemaining: Int = MAX_ATTEMPTS,
    val status: PairingStatus = PairingStatus.Pending,
    val createdAt: Instant
) {
    init {
        require(attemptsRemaining in 0..MAX_ATTEMPTS) {
            "attemptsRemaining out of range: $attemptsRemaining"
        }
    }

    /** True if the session is still in a state where [submitPin] is meaningful. */
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
     * Submit a PIN attempt. Returns either:
     * - Success(updated session in [PairingStatus.Succeeded]) if the PIN matches and the session is open & unexpired.
     * - Failure([DomainError]) if the session is expired/closed/invalid PIN
     *
     * On a wrong PIN this method does NOT mutate the receiver - callers must
     * call [decrementAttempt] to persist the failed-attempt side effect.
     * Keeping these two operations separate lets the application service log
     * + persist the failure before exposing it back to the UI.
     */
    fun submitPin(candidate: PairingCode, now: Instant): Result<PairingSession> {
        // Always project expiry forward first so a stale-but-correct PIN can't sneak in.
        val current = expireIfNeeded(now)
        if (!current.isOpen) {
            return Result.failure(DomainError.InvalidStateTransition("session ${current.status}"))
        }

        return if (candidate.digits == pin.digits) {
            Result.success(current.copy(status = PairingStatus.Succeeded))
        } else {
            val nextAttempts = (current.attemptsRemaining - 1).coerceAtLeast(0)
            val reason = if (nextAttempts == 0) "max attempts exceeded"
            else "wrong PIN; $nextAttempts attempt(s) remaining"
            Result.failure(DomainError.PermissionDenied(reason))
        }
    }

    /**
     * Persist the wrong-PIN side effect - decrement the counter and flip to
     * Failed when it hits zero. Called by the application service when it
     * wants to write the failed attempt back to the repository.
     */
    fun decrementAttempt(): PairingSession {
        if (!isOpen) return this
        val next = attemptsRemaining - 1
        return if (next <= 0) copy(attemptsRemaining = 0, status = PairingStatus.Failed)
        else copy(attemptsRemaining = next)
    }

    companion object {
        const val MAX_ATTEMPTS: Int = 3
        val DEFAULT_TTL: Duration = 5.minutes

        /**
         * Factory: a pristine pending session. The PIN, nonce, and session id
         * are caller-supplied so unit tests can be deterministic.
         */
        fun newPending(
            sessionId: String,
            peerDeviceId: DeviceId,
            direction: PairingDirection,
            pin: PairingCode,
            nonce: Nonce,
            peerFingerprint: Fingerprint,
            peerAlias: String,
            peerPlatform: DevicePlatform,
            createdAt: Instant,
            ttl: Duration = DEFAULT_TTL
        ): PairingSession = PairingSession(
            sessionId = sessionId,
            peerDeviceId = peerDeviceId,
            direction = direction,
            pin = pin,
            nonce = nonce,
            peerFingerprint = peerFingerprint,
            peerAlias = peerAlias,
            peerPlatform= peerPlatform,
            expiry = SessionExpiry(createdAt + ttl),
            createdAt = createdAt
        )

        /** Build a session whose `createdAt` defaults to `Clock.System.now()`. */
        fun newPendingNow(
            sessionId: String,
            peerDeviceId: DeviceId,
            direction: PairingDirection,
            pin: PairingCode,
            nonce: Nonce,
            peerFingerprint: Fingerprint,
            peerAlias: String,
            peerPlatform: DevicePlatform,
            ttl: Duration = DEFAULT_TTL
        ): PairingSession = newPending(
            sessionId = sessionId,
            peerDeviceId = peerDeviceId,
            direction = direction,
            pin = pin,
            nonce = nonce,
            peerFingerprint = peerFingerprint,
            peerAlias = peerAlias,
            peerPlatform= peerPlatform,
            createdAt = Clock.System.now(),
            ttl = ttl
        )
    }
}