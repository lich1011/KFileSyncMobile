package com.kfilesync.mobile.domain.port

import kotlinx.coroutines.flow.Flow

/**
 * Snapshot of battery state (T5.1, T5.4).
 *
 * - [levelFraction]: 0.0 - 1.0 or [SyncContext.BATTERY_UNKNOWN] (-1f) when
 * the OS doesn't expose a reading.
 * - [isCharging]: USB or AC power source connected.
 * - [isLow]: platform low-power signal. Android: `ACTION_BATTERY_LOW`;
 * iOS: `UIDevice.batteryLevel <= 0.20`. Used by [SyncPolicy] to short-
 * circuit decisions without re-evaluating thresholds.
 */
data class BatterySnapshot(
    val levelFraction: Float,
    val isCharging: Boolean,
    val isLow: Boolean
)

/**
 * Battery state port - sampled on demand by [SyncWorker] / [BGTaskScheduler]
 * handlers, observed continuously by the network-awareness orchestrator.
 *
 * Implementations are platform-specific:
 * - Android: `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` +
 * `ACTION_BATTERY_CHANGED` sticky intent.
 * - iOS: `UIDevice.batteryLevel` + `UIDevice.batteryStateDidChangeNotification`.
 * Note: iOS requires `UIDevice.isBatteryMonitoringEnabled = true`,
 * which the bootstrap turns on at startup.
 */
interface BatteryMonitor {
    /** Current snapshot. Cheap (single syscall). */
    suspend fun snapshot(): BatterySnapshot

    /**
     * Hot stream of snapshots. Emits the current value on subscription,
     * then on every battery-state change. Throttled to ~1 emission per
     * 10 s by the implementation to avoid notification storms.
     */
    fun observe(): Flow<BatterySnapshot>
}

/**
 * Network reachability snapshot (T5.4).
 *
 * - [hasNetwork]: any reachable interface (Wi-Fi, cellular, ethernet).
 * - [isWifi]: the *default* route is unmetered Wi-Fi. Stricter than
 * `hasNetwork` - we use this for the sync-policy gate.
 * - [isMetered]: the active connection has a usage cap (cellular hotspot,
 * metered Wi-Fi). Implies `!isWifi`.
 */
data class NetworkSnapshot(
    val hasNetwork: Boolean,
    val isWifi: Boolean,
    val isMetered: Boolean
)

/**
 * Network state port (T5.4).
 *
 * - Android: `ConnectivityManager.registerDefaultNetworkCallback` +
 * `getNetworkCapabilities` (NET_CAPABILITY_NOT_METERED, TRANSPORT_WIFI).
 * - iOS: `NWPathMonitor.start(queue:)` + `path.status` / `.isExpensive` /
 * `.usesInterfaceType(.wifi)`.
 *
 * The observable [observe] is the engine driving auto-pause / auto-resume:
 * the orchestrator pauses transfers when isWifi flips false, and resumes
 * when it flips back to true.
 */
interface NetworkMonitor {
    suspend fun snapshot(): NetworkSnapshot

    fun observe(): Flow<NetworkSnapshot>
}

/**
 * App foreground/background lifecycle port (T5.1).
 *
 * - Android: backed by `ProcessLifecycleOwner`'s `Lifecycle.State`.
 * - iOS: backed by `UIApplication.didBecomeActiveNotification` and
 * `willResignActiveNotification`.
 *
 * Only emits state changes; the initial value is `false` (background) and
 * flips to `true` after the user opens the app.
 */
interface AppLifecycleMonitor {
    suspend fun isForeground(): Boolean

    fun observe(): Flow<Boolean>
}