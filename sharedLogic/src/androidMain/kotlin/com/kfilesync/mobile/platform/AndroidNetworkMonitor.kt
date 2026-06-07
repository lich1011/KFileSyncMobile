package com.kfilesync.mobile.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.kfilesync.mobile.domain.port.NetworkMonitor
import com.kfilesync.mobile.domain.port.NetworkSnapshot
import io.github.aakira.napier.Napier
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * Android `NetworkMonitor` (T5.4) backed by `ConnectivityManager`.
 *
 * - Snapshot: query `getNetworkCapabilities(activeNetwork)` once. Cheap.
 * - Observe: register a `NetworkCallback` for the *default* network. The
 * callback delivers transitions (onAvailable / onLost / onCapabilitiesChanged),
 * each of which we map to a fresh [NetworkSnapshot] and emit.
 *
 * We register against the default network rather than a specific
 * `NetworkRequest` so the callback fires when the *default route* changes
 * - that's the sync-policy gate (sync flows always travel over the default
 * route). Side benefit: we don't burn battery keeping cellular alive when
 * Wi-Fi is preferred.
 */
class AndroidNetworkMonitor(
    private val context: Context
) : NetworkMonitor {

    private val cm by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override suspend fun snapshot(): NetworkSnapshot = readSnapshot()

    override fun observe(): Flow<NetworkSnapshot> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(readSnapshot()) }
            override fun onLost(network: Network) { trySend(readSnapshot()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(readSnapshot())
            }
        }

        // The default-network callback fires whenever the device's primary
        // route changes - that's what NetworkAwarenessService cares about.
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Napier.w("NetworkMonitor.observe register failed: ${it.message}") }

        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
        .onStart { emit(readSnapshot()) }
        .distinctUntilChanged()

    private fun readSnapshot(): NetworkSnapshot {
        val active = cm.activeNetwork
        if (active == null) {
            return NetworkSnapshot(hasNetwork = false, isWifi = false, isMetered = false)
        }
        val caps = cm.getNetworkCapabilities(active)
        if (caps == null) {
            return NetworkSnapshot(hasNetwork = false, isWifi = false, isMetered = false)
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        // 'NET_CAPABILITY_NOT_METERED' is the right signal for "is this
        // connection cheap?" - covers Wi-Fi with user-flagged metering and
        // cellular hotspots that the OS recognises as metered.
        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return NetworkSnapshot(
            hasNetwork = hasInternet,
            isWifi = isWifi,
            isMetered = isMetered
        )
    }

    companion object {
        // A request useful for callers who want explicit Wi-Fi targeting -
        // not used by the default-callback path here, but kept handy for
        // future T5.7 subnet-scan strategy that may register a Wi-Fi-only
        // network request.
        @Suppress("unused")
        val wifiRequest: NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    }
}