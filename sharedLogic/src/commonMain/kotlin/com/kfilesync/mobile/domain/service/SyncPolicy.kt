package com.kfilesync.mobile.domain.service

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Operating-condition snapshot consumed by [SyncPolicy.shouldSync] (T5.1).
 *
 * Sampled at decision time - the value is intentionally NOT stored in
 * [SyncPolicy] state because conditions change rapidly (a user can unplug
 * the charger and the next tick should re-evaluate). All four fields are
 * cheap to read on both platforms (BatteryManager on Android, UIDevice +
 * NWPathMonitor on iOS).
 *
 * - [isWifi]: 'true' iff the active default route is an unmetered Wi-Fi
 * interface. On Android we trust ConnectivityManager's NET_CAPABILITY_
 * NOT_METERED bit; on iOS we trust `NWPath.isExpensive == false &&
 * usesInterfaceType(.wifi)`. False covers cellular / metered hotspot
 * / no network at all (the latter is also handled by the WorkManager
 * constraint).
 * - [isCharging]: 'true' iff the device is currently drawing power
 * (USB or AC). On iOS this maps to `UIDevice.batteryState in {.charging,
 * .full}`. Ignored when [isAppInForeground] is true.
 * - [batteryLevel]: 0.0 - 1.0. Sentinel -1.0 means "unknown" (e.g. battery
 * monitoring disabled on iOS); the default policy treats unknown as 1.0
 * so we never refuse to sync just because we can't read the gauge.
 * - [isAppInForeground]: 'true' if the user is actively viewing KFileSync.
 * Aggressive policies relax their gating in the foreground because the
 * user is observing the sync and presumably wants it to run.
 */
data class SyncContext(
    val isWifi: Boolean,
    val isCharging: Boolean,
    val batteryLevel: Float,
    val isAppInForeground: Boolean
) {
    companion object {
        /** Sentinel for unknown battery readings (iOS sim, monitoring off). */
        const val BATTERY_UNKNOWN: Float = -1.0f
    }
}

/**
 * Sync gating policy (design doc §8.2, T5.1).
 *
 * Implementations are pure: same input -> same output. Side effects (logging,
 * reading the OS battery gauge) happen at the caller - the caller is
 * 'SyncWorker' (Android) or 'BGAppRefreshTask' handler (iOS).
 *
 * 'syncInterval()' is consumed by the scheduler to set the period of the
 * WorkManager / BGTaskScheduler request. The scheduler may run more often
 * than the interval (system batching) but should not run *less* often.
 */
interface SyncPolicy {
    /** Should a sync session start under the given conditions? */
    fun shouldSync(context: SyncContext): Boolean

    /** Target period for scheduled syncs. */
    fun syncInterval(): Duration

    /** Stable identifier persisted in Config table. */
    val id: SyncPolicyId
}

/**
 * Wire-stable identifier for [SyncPolicy] selection. Persisted in the
 * `config` table under key `sync.policy`. Keep these strings stable - they
 * are read on every app launch and a typo would silently downgrade users
 * to the default policy.
 */
enum class SyncPolicyId(val wire: String) {
    Default("default"),
    Aggressive("aggressive"),
    ChargingOnly("charging_only");

    companion object {
        fun fromWire(s: String?): SyncPolicyId =
            entries.firstOrNull { it.wire == s } ?: Default
    }
}

/**
 * Phase 5 default: balanced battery vs freshness.
 *
 * Rules:
 * - Wi-Fi required (never burns cellular data).
 * - Either charging, OR battery > 20 %. Below 20 % we defer to avoid
 * hastening a low-battery shutdown. Foreground use overrides this;
 * if the user is in the app, they want sync regardless of battery.
 *
 * Interval: 15 minutes - matches WorkManager's PeriodicWorkRequest floor
 * and BGAppRefreshTask's typical opportunistic window.
 */
class DefaultSyncPolicy : SyncPolicy {

    override fun shouldSync(context: SyncContext): Boolean {
        if (!context.isWifi) return false
        if (context.isAppInForeground) return true
        if (context.isCharging) return true
        val level = if (context.batteryLevel == SyncContext.BATTERY_UNKNOWN) 1.0f else context.batteryLevel
        return level > 0.2f
    }

    override fun syncInterval(): Duration = 15.minutes

    override val id: SyncPolicyId = SyncPolicyId.Default
}

/**
 * Phase 5 aggressive: tighter interval, looser gating.
 *
 * Rules: Wi-Fi only; ignore battery + charging gates entirely. Suitable for
 * power users who want near-real-time sync and are willing to pay the
 * battery cost.
 *
 * Interval: 5 minutes - short enough that "save in desktop editor, see on
 * phone within minutes" feels responsive; long enough that the radio still
 * gets to idle between sessions.
 */
class AggressiveSyncPolicy : SyncPolicy {

    override fun shouldSync(context: SyncContext): Boolean = context.isWifi

    override fun syncInterval(): Duration = 5.minutes

    override val id: SyncPolicyId = SyncPolicyId.Aggressive
}

/**
 * Phase 5 charging-only: strictest battery-friendly mode.
 *
 * Rules: Wi-Fi AND charging. Foreground does not override - if the user
 * picked this policy they explicitly opted out of on-the-go syncing.
 *
 * Interval: 30 minutes - long because the user has already signalled they
 * don't mind staleness; running more often would just waste energy on
 * redundant index exchanges.
 */
class ChargingOnlySyncPolicy : SyncPolicy {

    override fun shouldSync(context: SyncContext): Boolean =
        context.isWifi && context.isCharging

    override fun syncInterval(): Duration = 30.minutes

    override val id: SyncPolicyId = SyncPolicyId.ChargingOnly
}

/**
 * Factory - used by the SettingsViewModel to map an enum selection to a fresh policy instance.
 */
fun syncPolicyFor(id: SyncPolicyId): SyncPolicy = when (id) {
    SyncPolicyId.Default -> DefaultSyncPolicy()
    SyncPolicyId.Aggressive -> AggressiveSyncPolicy()
    SyncPolicyId.ChargingOnly -> ChargingOnlySyncPolicy()
}