package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.domain.event.DomainEvent
import com.kfilesync.mobile.domain.event.PairingCompleted
import com.kfilesync.mobile.domain.event.TrustRevoked
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.TrustBootstrapState
import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import io.github.aakira.napier.Napier
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Shared in-memory snapshot of the pinned-peer fingerprint set + the
 * "ever paired" flag. (Hardening for security issues #7, #8, #9.)
 *
 * The TLS pinning trust managers (`PinningTrustManager` on Android,
 * `IosPinningChallengeHandler` on iOS) used to call `findPaired()` from
 * inside the TLS handshake callback via `runBlocking`. That was bad for
 * two reasons:
 *
 * 1. Deadlock risk: the handshake callback runs on OkHttp's TLS thread
 * (Android) or URLSession's delegate queue (iOS). Blocking that
 * thread on a coroutine that wants to hop to Dispatchers.Default can
 * deadlock under load.
 * 2. DB pressure: every TLS handshake hits SQLite, even for a single
 * short-lived '/info' probe.
 *
 * This cache holds the fingerprint set + the bootstrap flag in lock-free
 * atomics and lets the TLS callback do a pure in-memory lookup. The cache
 * is invalidated on every `PairingCompleted` / `TrustRevoked` event via a
 * SharedFlow subscriber.
 *
 * Threading: pure reads (`pinnedFingerprints`, `hasEverPaired`) are safe
 * from any thread. Refreshes happen on a worker dispatcher.
 */
class PinnedTrustSnapshot(
    private val deviceRepository: DeviceRepository,
    private val trustBootstrapState: TrustBootstrapState,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val pinned = atomic<Set<String>>(emptySet())
    private val everPaired = atomic(false)

    /** Hot-path read for the TLS handshake. O(1) atomic load. */
    fun pinnedFingerprints(): Set<String> = pinned.value

    /** Hot-path read for the TLS handshake. O(1) atomic load. */
    fun hasEverPaired(): Boolean = everPaired.value

    /** Eager refresh - call from bootstrap and after pairing/revocation events. */
    suspend fun refresh() {
        val fps = runCatching {
            deviceRepository.findPaired()
                .mapNotNull { device ->
                    (device.state as? DeviceState.Paired)?.let {
                        fingerprintOf(it.certificatePem)
                    }
                }
                .toSet()
        }.getOrElse {
            Napier.w("PinnedTrustSnapshot.refresh failed: ${it.message}")
            return
        }
        pinned.value = fps
        val flag = runCatching { trustBootstrapState.hasEverPaired() }.getOrDefault(false)
        everPaired.value = flag
    }

    /** Subscribe to the event bus and auto-refresh on pairing / revocation. */
    fun bind(eventBus: EventBus) {
        scope.launch { refresh() }
        eventBus.events()
            .filter { it is PairingCompleted || it is TrustRevoked }
            .onEach { ev: DomainEvent ->
                if (ev is PairingCompleted) {
                    runCatching { trustBootstrapState.markPaired() }
                }
                refresh()
            }
            .launchIn(scope)
        Napier.d("PinnedTrustSnapshot bound to event bus")
    }

    private fun fingerprintOf(pem: String): String {
        val der = com.kfilesync.mobile.infrastructure.crypto.PemUtils.pemToDer(pem)
            ?: ByteArray(0)
        return HashProvider.sha256(der).toHexLower()
    }
}