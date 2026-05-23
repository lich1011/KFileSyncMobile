package com.kfilesync.mobile.domain.model

import kotlin.time.Instant

@kotlin.jvm.JvmInline
value class PairingCode(val digits: String) // 6-digit PIN

@kotlin.jvm.JvmInline
value class Nonce(val value: String)

data class SessionExpiry(val expiresAt: Instant)

/**
 * PairingSession aggregate root.
 * Phase 0 placeholder - Phase 1 (T1.3) fills in: 6-digit PIN, 5-minute timeout,
 * 3-attempt limit, certificate exchange on both-side confirmation.
 */
data class PairingSession(
    val sessionId: String,
    val peerDeviceId: DeviceId,
    val pin: PairingCode,
    val nonce: Nonce,
    val expiry: SessionExpiry,
    val attemptsRemaining: Int = 3
)