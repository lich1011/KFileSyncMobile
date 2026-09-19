package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.infrastructure.crypto.SecureRng
import com.kfilesync.mobile.infrastructure.crypto.nextHex
import kotlin.time.Clock

/**
 * Client-side anti-replay header signer (audit gap fix).
 *
 * The server ([HttpServer.verifyAntiReplay], T5.3) requires three headers on
 * every *mutating* route - `X-Device-Id`, `X-Timestamp` (Unix epoch millis),
 * `X-Nonce` - plus an optional `X-Fingerprint` cross-check against the paired
 * peer's stored certificate. Production DI wires a non-null `NonceWindow` with
 * `allowMissingNonceWindow = false`, so a request that omits these headers is
 * rejected with `401 missing_anti_replay_headers`.
 *
 * Before this class existed, [LanSyncHttpClient]'s POST methods sent none of
 * those headers, so every cross-device mutating request (pairing, transfer,
 * share) failed on real hardware - the loopback unit tests never caught it
 * because the fakes bypass HTTP entirely. This signer closes that gap.
 *
 * Each call to [headers] produces a *fresh* timestamp + 128-bit CSPRNG nonce,
 * so the same request body re-sent later carries a different nonce and the
 * server's sliding window treats it as a new (not replayed) request.
 *
 * The asserted device id + fingerprint are *our own* identity - the receiving
 * peer looks them up against the certificate it stored for us at pairing time,
 * so they must match what we advertised on `/info` and exchanged on
 * `/pair/confirm`.
 */
fun interface AntiReplaySigner {
    /** Header name -> value pairs to attach to a mutating request. */
    fun headers(): List<Pair<String, String>>
}

/**
 * Default [AntiReplaySigner] backed by the local device identity.
 *
 * Reuses [HttpServer]'s header-name constants so the wire contract can't
 * drift between the two sides.
 */
class AntiReplayHeaderProvider(
    private val localIdentityProvider: LocalIdentityProvider,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val nonceProvider: () -> String = { SecureRng.nextHex(16) }
) : AntiReplaySigner {

    override fun headers(): List<Pair<String, String>> {
        val identity = localIdentityProvider.current()
        return listOf(
            HttpServer.HEADER_DEVICE_ID to identity.deviceId.value,
            HttpServer.HEADER_TIMESTAMP to nowMillis().toString(),
            HttpServer.HEADER_NONCE to nonceProvider(),
            HttpServer.HEADER_FINGERPRINT to identity.fingerprint.hex
        )
    }
}