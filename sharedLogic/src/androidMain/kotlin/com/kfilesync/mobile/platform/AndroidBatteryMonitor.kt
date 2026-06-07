package com.kfilesync.mobile.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.kfilesync.mobile.domain.port.BatteryMonitor
import com.kfilesync.mobile.domain.port.BatterySnapshot
import com.kfilesync.mobile.domain.service.SyncContext
import io.github.aakira.napier.Napier
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample

/**
 * Android `BatteryMonitor` (T5.1, T5.4).
 *
 * Two read paths:
 * - `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` for the
 * point-in-time level - cheap, no broadcast required.
 * - Sticky `ACTION_BATTERY_CHANGED` for charging state - that broadcast
 * is sticky on Android, so a fresh `registerReceiver(null, filter)`
 * call returns the current Intent synchronously.
 *
 * For the [observe] flow we register a real `BroadcastReceiver` on
 * `ACTION_POWER_CONNECTED` / `_DISCONNECTED` + `ACTION_BATTERY_LOW` / `_OKAY`.
 * Those four cover all interesting transitions; raw level changes fire
 * every 1 % which would be wasteful, so we sample the flow at 10 s
 * intervals.
 */
class AndroidBatteryMonitor(
    private val context: Context
) : BatteryMonitor {

    override suspend fun snapshot(): BatterySnapshot = readSnapshot()

    override fun observe(): Flow<BatterySnapshot> = callbackFlow {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(readSnapshot())
            }
        }

        runCatching {
            // RECEIVER_NOT_EXPORTED is API 33+; we use the no-flags variant
            // for backward compatibility (minSdk=26). These four actions are
            // protected by the system so anyone can subscribe safely.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }.onFailure { Napier.w("BatteryMonitor.observe register failed: ${it.message}") }

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
        .conflate()
        .sample(10_000L)
        .onStart { emit(readSnapshot()) }
        .distinctUntilChanged()

    private fun readSnapshot(): BatterySnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val level = if (capacity in 0..100) capacity / 100f else SyncContext.BATTERY_UNKNOWN

        val sticky: Intent? = runCatching {
            // No flags needed for sticky broadcast read; the API explicitly
            // permits passing a null BroadcastReceiver.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val statusRaw = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = statusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusRaw == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0

        val isLow = level != SyncContext.BATTERY_UNKNOWN && level <= 0.15f
        return BatterySnapshot(levelFraction = level, isCharging = charging, isLow = isLow)
    }
}