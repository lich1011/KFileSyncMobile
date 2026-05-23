package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.port.DeviceInfo
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import com.kfilesync.mobile.domain.port.DiscoveryProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinator that owns the live "discovered devices" state stream (T1.2).
 *
 * Wraps the platform [DiscoveryProvider] adapter and adds:
 * - de-duplication keyed by `DeviceId` so a device that announces twice
 * doesn't show up twice in the UI
 * - an explicit "self filter" so the local device's own advertisement
 * never appears as a peer
 * - a single [StateFlow] the ViewModel layer can `collectAsState()`
 *
 * Lives in commonMain because the de-dup + filter logic is platform-agnostic;
 * the only platform-specific thing is the [DiscoveryProvider] adapter we
 * delegate to.
 */
class DiscoveryCoordinator(
    private val provider: DiscoveryProvider,
    private val localIdentityProvider: LocalIdentityProvider
) {
    private val mutex = Mutex()
    private val byId = linkedMapOf<String, DiscoveredDevice>()
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())

    /** Cold view; collect from a ViewModel. */
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    /** Start advertising ourselves AND listening for peers. Idempotent. */
    suspend fun start() {
        val identity = localIdentityProvider.current()
        provider.announce(
            DeviceInfo(
                deviceId = identity.deviceId,
                alias = identity.alias,
                platform = identity.platform,
                fingerprint = identity.fingerprint,
                port = identity.port
            )
        )

        provider.listen { peer ->
            // Filter out our own announcement.
            if (peer.deviceId == identity.deviceId) return@listen

            // De-dup by device id. Keep the freshest seen address list.
            // We don't suspend in the adapter callback path (the adapter
            // hands us a synchronous (DiscoveredDevice) -> Unit), so we just
            // update the StateFlow non-blockingly.
            byId[peer.deviceId.value] = peer
            _devices.value = byId.values.toList()
            Napier.d("discovered ${peer.alias} (${peer.platform})")
        }
    }

    /** Stop advertising and listening, and clear the current snapshot. */
    suspend fun stop() {
        provider.stop()
        mutex.withLock {
            byId.clear()
            _devices.value = emptyList()
        }
    }

    /**
     * Inject a manually-probed peer into the discovery stream (T1.2 fallback path).
     * Used after [ManualIpProbe.probe] returns a hit so the UI sees it next to
     * mDNS-discovered devices.
     */
    suspend fun addManual(peer: DiscoveredDevice) {
        val identity = localIdentityProvider.current()
        if (peer.deviceId == identity.deviceId) return
        mutex.withLock {
            byId[peer.deviceId.value] = peer
            _devices.value = byId.values.toList()
        }
    }
}