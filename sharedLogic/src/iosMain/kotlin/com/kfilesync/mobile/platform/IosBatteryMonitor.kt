package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.BatteryMonitor
import com.kfilesync.mobile.domain.port.BatterySnapshot
import com.kfilesync.mobile.domain.service.SyncContext
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

/**
 * iOS `BatteryMonitor` (T5.1) backed by `UIDevice`.
 *
 * - `UIDevice.currentDevice.batteryLevel` is 0.0 - 1.0 or -1.0 if monitoring is off.
 * - `UIDevice.currentDevice.batteryState` enum: unknown / unplugged / charging / full.
 *
 * `isBatteryMonitoringEnabled` must be true. The iosBootstrap turns it on
 * once at startup; we re-enable inside [snapshot] just in case (idempotent).
 *
 * Observable: subscribes to two notifications - battery level + state -
 * and emits a fresh snapshot on each. The simulator returns `-1.0` for
 * Level even with monitoring enabled; tests on hardware show actual values.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBatteryMonitor : BatteryMonitor {

    init {
        // Enabling monitoring causes the OS to start updating `batteryLevel`
        // and emit the relevant notifications. No-op if already enabled.
        UIDevice.currentDevice.setBatteryMonitoringEnabled(true)
    }

    override suspend fun snapshot(): BatterySnapshot = readSnapshot()

    override fun observe(): Flow<BatterySnapshot> = callbackFlow {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue

        val levelObserver = center.addObserverForName(
            name = "UIDeviceBatteryLevelDidChangeNotification",
            `object` = null,
            queue = queue
        ) { _ -> trySend(readSnapshot()) }

        val stateObserver = center.addObserverForName(
            name = "UIDeviceBatteryStateDidChangeNotification",
            `object` = null,
            queue = queue
        ) { _ -> trySend(readSnapshot()) }

        awaitClose {
            runCatching {
                center.removeObserver(levelObserver)
                center.removeObserver(stateObserver)
            }.onFailure { Napier.w("BatteryMonitor.observe cleanup failed: ${it.message}") }
        }
    }
        .onStart { emit(readSnapshot()) }
        .distinctUntilChanged()

    private fun readSnapshot(): BatterySnapshot {
        val device = UIDevice.currentDevice
        if (!device.batteryMonitoringEnabled) device.setBatteryMonitoringEnabled(true)

        val raw = device.batteryLevel
        val level = if (raw < 0f) SyncContext.BATTERY_UNKNOWN else raw

        val state = device.batteryState
        val charging = state == UIDeviceBatteryState.UIDeviceBatteryStateCharging || state == UIDeviceBatteryState.UIDeviceBatteryStateFull

        // Apple's "low power mode" is a separate flag (NSProcessInfo.isLowPowerModeEnabled)
        // - we treat <= 15 % as low for parity with Android's ACTION_BATTERY_LOW.
        val isLow = level != SyncContext.BATTERY_UNKNOWN && level <= 0.15f

        @Suppress("UNUSED_VARIABLE")
        val unknown = state == UIDeviceBatteryState.UIDeviceBatteryStateUnknown || state == UIDeviceBatteryState.UIDeviceBatteryStateUnplugged

        return BatterySnapshot(levelFraction = level, isCharging = charging, isLow = isLow)
    }
}