package com.kfilesync.mobile.domain.port

/**
 * One-bit persistent flag: have we *ever* successfully paired at least one
 * device on this install? (T7.x - security review hardening.)
 *
 * The [PinningTrustManager] / [IosPinningChallengeHandler] use this to
 * decide whether the "bootstrap" window (accept the first handshake when
 * the pinned set is empty) is still legitimate. Without this flag, the
 * window silently re-opens any time the user revokes their last paired
 * device - at which point an attacker who knows the user is about to
 * re-pair can MITM the next handshake.
 *
 * Adapters back this with the `config` K/V table (key:
 * `trust.ever_paired`), so the flag survives process restarts.
 *
 * Lifecycle:
 * - default `false` on fresh install.
 * - flipped to `true` the first time pairing completes (any direction).
 * Implementation: a small handler in `SecurityHandler` subscribed to
 * `PairingCompleted`.
 * - never reset (revocation does NOT clear it). Once a user has paired,
 * they have explicitly trusted at least one peer at some point, and we
 * refuse to silently re-enter the OOB window.
 */
interface TrustBootstrapState {
    /** Returns true iff at least one successful pairing has ever happened. */
    suspend fun hasEverPaired(): Boolean

    /** Mark the flag set. Idempotent. */
    suspend fun markPaired()
}