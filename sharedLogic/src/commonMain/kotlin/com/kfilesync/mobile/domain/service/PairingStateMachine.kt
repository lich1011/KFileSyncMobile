package com.kfilesync.mobile.domain.service

/**
 * Plain, cross-platform mirror of core's `domain::PairingSession` record
 * (see `kfilesync-core/src/domain/pairing.rs`). `commonMain` cannot
 * reference `uniffi.kfilesync_core.PairingSession` directly (JVM/Android-only
 * JNA bindings), so this is the type [PairingStateMachine]'s callers hold and
 * persist instead.
 */
data class PairingSessionState(
    val requestId: String,
    val targetDeviceId: String,
    val ourPin: String,
    val expiresAtMs: Long,
    val attempts: Int,
    val maxAttempts: Int
)

/** Mirror of core's `trust::pairing_state::PinVerdict`. */
enum class PinVerdict { Accepted, Wrong, Expired, MaxAttemptsExceeded }

/**
 * Wraps core's pairing state machine (Sprint 5:
 * `trust::pairing_state::{start_outbound, receive_incoming}` +
 * `FfiPairingSession::verify_peer_pin`).
 *
 * Deliberately does NOT expose `record_peer_pin` / core's
 * `PairingSession.expected_their_pin`: `verify_peer_pin` only ever compares
 * `provided_pin` against `session.our_pin` (core's ground truth), never
 * against `expected_their_pin` - that field only matters for core's
 * `prepare_confirm` helper (building an outgoing `/pair/confirm` body),
 * which mobile does not use (`PairingService` already has the user-typed
 * candidate PIN in hand at the moment it calls [verifyPeerPin], so there is
 * nothing to "record" ahead of time).
 *
 * Does not use the `PreparedRequest`/body/headers half of
 * `start_outbound`/`prepare_confirm` either: core's `anti_replay_headers()`
 * only attaches device-id/timestamp/nonce, missing the `X-Fingerprint`
 * header mobile's own [com.kfilesync.mobile.infrastructure.network.AntiReplaySigner]
 * already sends, and UniFFI cannot export the generic `protocol::codec`
 * functions core uses to build that body (confirmed in Sprint 4). Mobile
 * keeps building its own `PairRequestDto`/`PairConfirmDto` via
 * `ApiModels.kt` + `LanSyncHttpClient`; this class only supplies the PIN
 * state-machine semantics.
 */
expect class PairingStateMachine() {
    fun startOutbound(
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
    ): PairingSessionState

    /**
     * [requestId]/[fromDeviceId] are the only fields of the inbound
     * `PairRequestDto` core's `receive_incoming` actually reads into the
     * resulting session (`request_id`, `target_device_id`) - the rest of
     * the DTO's fields play no part in this computation.
     */
    fun receiveIncoming(
        requestId: String,
        fromDeviceId: String,
        ourPin: String,
        maxAttempts: Int,
        nowMs: Long,
        pinTtlMs: Long
    ): PairingSessionState

    fun verifyPeerPin(session: PairingSessionState, providedPin: String, nowMs: Long): Pair<PairingSessionState, PinVerdict>
}