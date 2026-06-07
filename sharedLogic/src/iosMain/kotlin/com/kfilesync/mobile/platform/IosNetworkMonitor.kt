package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.NetworkMonitor
import com.kfilesync.mobile.domain.port.NetworkSnapshot
import io.github.aakira.napier.Napier
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue

/**
 * iOS 'NetworkMonitor' (T5.4) backed by 'nw_path_monitor' (Network.framework).
 *
 * Single long-lived monitor started lazily on first interest; cancelled
 * when the last subscriber goes away. Snapshots cached in a hot
 * StateFlow so [snapshot] is O(1).
 *
 * - "Wi-Fi" in our domain model = path is satisfied + uses wifi interface
 * + not "expensive" (Apple's hotspot/metered signal).
 * - "Metered" = path is expensive.
 *
 * Why not the deprecated `SCNetworkReachability`: Network.framework gives
 * us the expensive bit cleanly and is what Apple recommends since iOS 12.
 */
@OptIn(ExperimentalForeignApi::class)
class IosNetworkMonitor : NetworkMonitor {

    private val current = MutableStateFlow(
        NetworkSnapshot(hasNetwork = false, isWifi = false, isMetered = false)
    )

    private val subscribers = atomic(0)

    // nw_path_monitor_t is a platform alias that we don't need to name.
    private var monitor: platform.Network.nw_path_monitor_t? = null

    override suspend fun snapshot(): NetworkSnapshot {
        ensureMonitorRunning()
        return current.value
    }

    override fun observe(): Flow<NetworkSnapshot> = callbackFlow {
        ensureMonitorRunning()
        subscribers.incrementAndGet()

        // Forward StateFlow updates into the callbackFlow channel.
        val scope = CoroutineScope(coroutineContext)
        val forwarder = scope.launch {
            current.collect { trySend(it) }
        }
        awaitClose {
            forwarder.cancel()
            if (subscribers.decrementAndGet() == 0) {
                stopMonitor()
            }
        }
    }
        .onStart { emit(current.value) }
        .distinctUntilChanged()

    private fun ensureMonitorRunning() {
        if (monitor != null) return
        val mon = nw_path_monitor_create() ?: run {
            Napier.w("IosNetworkMonitor: nw_path_monitor_create returned null")
            return
        }

        nw_path_monitor_set_queue(
            mon,
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
        )

        nw_path_monitor_set_update_handler(mon) { path ->
            if (path == null) return@nw_path_monitor_set_update_handler
            val satisfied = nw_path_get_status(path) == nw_path_status_satisfied
            val isWifi = nw_path_uses_interface_type(path, nw_interface_type_wifi)
            val expensive = nw_path_is_expensive(path)
            current.value = NetworkSnapshot(
                hasNetwork = satisfied,
                isWifi = satisfied && isWifi && !expensive,
                isMetered = expensive
            )
        }

        nw_path_monitor_start(mon)
        monitor = mon
        Napier.d("IosNetworkMonitor: nw_path_monitor started")
    }

    private fun stopMonitor() {
        val mon = monitor ?: return
        nw_path_monitor_cancel(mon)
        monitor = null
    }
}