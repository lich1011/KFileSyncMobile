package com.kfilesync.mobile.application.handler

import com.kfilesync.mobile.domain.event.TrustRevoked
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.KeyStore
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cross-cutting handler for security-related domain events (T1.5).
 *
 * Current responsibilities:
 * - `TrustRevoked` -> scrub the peer's stored cert from the device repo
 * (already mostly handled by `PairingService.revoke` which writes the
 * status flag; this handler is where Phase 2+ will additionally cancel
 * in-flight transfers and erase share memberships).
 *
 * Wired at app boot via [register].
 */
class SecurityHandler(
    private val deviceRepository: DeviceRepository,
    private val keyStore: KeyStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    fun register(eventBus: EventBus) {
        eventBus.subscribe(TrustRevoked::class) { evt ->
            scope.launch { onTrustRevoked(evt) }
        }
        Napier.i("SecurityHandler subscribed to TrustRevoked")
    }

    private suspend fun onTrustRevoked(event: TrustRevoked) {
        // Phase 1: clear any per-device key material we might have stored.
        // The local-device identity key stays - we revoke trust in *peers*,
        // not in ourselves.
        runCatching { keyStore.deletePrivateKey(event.deviceId) }
            .onFailure { Napier.w("deletePrivateKey(${event.deviceId.value}) failed: ${it.message}") }

        // Phase 2 (T2.7) will additionally:
        // - cancel active TransferJobs whose peer == event.deviceId
        // - drop the peer from every share's member list
        // - close any open HTTP connections to the peer's addresses
        Napier.i("SecurityHandler scrubbed key material for ${event.deviceId.value}")
    }
}