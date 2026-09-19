package com.kfilesync.mobile.domain.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Anti-replay sliding window (T5.3, design doc §9.1).
 *
 * Rejects requests whose (timestamp, nonce) pair we've already seen, or
 * whose timestamp falls outside an acceptable freshness window. Mirrors the
 * desktop client's anti-replay so a recorded HTTPS POST can't be replayed
 * even if an attacker somehow got past TLS pinning.
 *
 * Two-layer defence:
 * 1. **Timestamp window**: reject anything older than `windowSize` or
 * more than `clockSkew` in the future. Default 5 minutes matches
 * desktop, generous enough to absorb mobile clock drift, tight enough
 * that replay opportunities collapse to a 10-minute window.
 * 2. **Nonce uniqueness**: every accepted request adds its nonce to a
 * set keyed by `deviceId`. Entries older than `windowSize` are
 * evicted opportunistically on each call.
 *
 * Memory bound: O(active_peers * requests_per_window). For a normal mobile
 * client this is at most a few hundred entries. The mutex covers a single
 * Map and is held only for the verify+insert critical section.
 *
 * Lives in 'domain.service' because it's a pure piece of business policy
 * no I/O, no platform deps. Wired into the HttpServer route handlers in
 * Phase 5 (T5.3); the integration is intentionally a *thin* call: routes
 * pass *(deviceId, timestamp, nonce)* from the request headers and abort
 * with 401 on `accept = false`.
 */
class NonceWindow(
    private val windowSize: Duration = 5.minutes,
    private val clockSkew: Duration = 5.minutes,
    private val clock: () -> Instant = { Clock.System.now() }
) {
    /** One-shot result, carrying the rejection reason lets log lines distinguish replay vs skew. */
    sealed class Verdict {
        data object Accepted : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

    /**
     * Fine-grained verdict matching kfilesync-core's `NonceVerdict` (see
     * `service/nonce_window.rs`) one-for-one, so the conformance runner
     * (kfilesync-conformance/fixtures/nonce_window/) can assert against it
     * directly instead of pattern-matching [Verdict]'s free-text reason.
     */
    enum class DetailedVerdict {
        FRESH, STALE_TIMESTAMP, FUTURE_TIMESTAMP, REPLAY, BLANK_NONCE
    }

    /** Per-device sets of seen nonces with arrival timestamps for eviction. */
    private val seen = mutableMapOf<String, MutableMap<String, Long>>()
    private val mutex = Mutex()

    /**
     * Validate and remember a fresh nonce.
     *
     * - Rejects if [timestamp] is more than `windowSize` in the past.
     * - Rejects if [timestamp] is more than `clockSkew` in the future
     * (a peer with a wildly future clock is either misconfigured or
     * attempting to widen the replay window - both warrant rejection).
     * - Rejects if [nonce] has been observed for [deviceId] within the
     * window.
     * - On accept, records the (nonce, now) pair and prunes stale entries.
     */
    suspend fun verify(deviceId: String, timestamp: Instant, nonce: String): Verdict {
        val nowMs = clock().toEpochMilliseconds()
        val timestampMs = timestamp.toEpochMilliseconds()
        return when (mutex.withLock { verifyAndRecordLocked(deviceId, timestampMs, nonce, nowMs) }) {
            DetailedVerdict.FRESH -> Verdict.Accepted
            DetailedVerdict.BLANK_NONCE -> Verdict.Rejected("blank nonce")
            DetailedVerdict.STALE_TIMESTAMP -> Verdict.Rejected("timestamp too old: ${nowMs - timestampMs}ms")
            DetailedVerdict.FUTURE_TIMESTAMP -> Verdict.Rejected("timestamp too far in future: ${timestampMs - nowMs}ms")
            DetailedVerdict.REPLAY -> Verdict.Rejected("nonce replay")
        }
    }

    /**
     * Same check as [verify], but with an explicit `nowMs`/window/skew per
     * call instead of the constructor-fixed clock and durations, and a
     * [DetailedVerdict] result — this is what the conformance runner drives
     * directly against `nonce_window/verify_and_record.json`.
     *
     * Check order mirrors `verify_and_record` in `service/nonce_window.rs`
     * exactly: blank nonce, then stale timestamp, then future timestamp,
     * then replay. Both window bounds are inclusive.
     */
    suspend fun verifyAndRecord(
        deviceId: String,
        timestampMs: Long,
        nonce: String,
        nowMs: Long,
        windowSizeMs: Long,
        clockSkewMs: Long
    ): DetailedVerdict = mutex.withLock {
        verifyAndRecordLocked(deviceId, timestampMs, nonce, nowMs, windowSizeMs, clockSkewMs)
    }

    private fun verifyAndRecordLocked(
        deviceId: String,
        timestampMs: Long,
        nonce: String,
        nowMs: Long,
        windowSizeMs: Long = windowSize.inWholeMilliseconds,
        clockSkewMs: Long = clockSkew.inWholeMilliseconds
    ): DetailedVerdict {
        if (nonce.isBlank()) return DetailedVerdict.BLANK_NONCE

        val ageMs = nowMs - timestampMs
        if (ageMs > windowSizeMs) return DetailedVerdict.STALE_TIMESTAMP
        if (-ageMs > clockSkewMs) return DetailedVerdict.FUTURE_TIMESTAMP

        val deviceBucket = seen.getOrPut(deviceId) { mutableMapOf() }
        // Opportunistic prune of stale entries for this device.
        val cutoff = nowMs - windowSizeMs
        val iter = deviceBucket.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.value < cutoff) iter.remove()
        }

        if (deviceBucket.containsKey(nonce)) {
            return DetailedVerdict.REPLAY
        }

        deviceBucket[nonce] = nowMs
        return DetailedVerdict.FRESH
    }

    /** Bucket size for diagnostics / tests. */
    suspend fun trackedNonceCount(deviceId: String): Int = mutex.withLock {
        seen[deviceId]?.size ?: 0
    }

    /** Wipe everything used on trust revocation cascade. */
    suspend fun clear(deviceId: String) = mutex.withLock {
        seen.remove(deviceId)
        Unit
    }
}