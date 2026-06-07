package com.kfilesync.mobile.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kfilesync.mobile.application.service.SyncAppService
import com.kfilesync.mobile.application.service.SyncPolicyProvider
import com.kfilesync.mobile.application.service.TombstoneCleanupService
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.port.AppLifecycleMonitor
import com.kfilesync.mobile.domain.port.BatteryMonitor
import com.kfilesync.mobile.domain.port.NetworkMonitor
import com.kfilesync.mobile.domain.port.ShareRepository
import com.kfilesync.mobile.domain.service.SyncContext
import io.github.aakira.napier.Napier
import java.util.concurrent.TimeUnit
import org.koin.core.context.GlobalContext

/**
 * Periodic background sync worker (Phase 4 T4.7, design doc §8.1).
 *
 * Why a `CoroutineWorker` rather than a foreground service:
 * - WorkManager batches `PeriodicWorkRequest`s with other system jobs so
 * they fire opportunistically when the radio is already awake (saves
 * battery). A foreground service would keep us pinned in memory
 * between syncs, which is wasteful for the 15-minute cadence.
 * - The platform respects our `NetworkType.UNMETERED` constraint - the
 * worker won't run on cellular even if the user manually triggers it
 * mid-roaming.
 *
 * Phase 5 (T5.1): the worker also consults [SyncPolicyProvider] to decide
 * whether to actually run sync once it's woken up. WorkManager's constraint
 * machinery can wake us on Wi-Fi but can't express "Wi-Fi AND charging" or
 * "Wi-Fi AND battery > 20 %", so we re-check those in-process via
 * [BatteryMonitor] + [NetworkMonitor] + [AppLifecycleMonitor], assemble a
 * [SyncContext], and skip the body if the policy declines. We still report
 * `Result.success()` in that case - skipping is the correct outcome, not a
 * failure to be retried.
 *
 * The schedule period also comes from the policy (`syncInterval()`) - we
 * pass it in via [schedule] and `KFileSyncApplication.onCreate` calls
 * `schedule(this, policy.syncInterval().inWholeMinutes)` so a policy change
 * propagates on next app launch.
 *
 * Battery / network policy (design doc §8.2):
 * - Required network: UNMETERED (Wi-Fi only) - never burn cellular data.
 * - Backoff: EXPONENTIAL starting at 1 minute. After three consecutive
 * `Result.retry()`s we surrender with `Result.failure()` rather than
 * keep waking up; the next scheduled period picks it back up.
 *
 * Koin Lookup happens inside `doWork()` rather than in the constructor:
 * `CoroutineWorker` is instantiated by WorkManager's `WorkerFactory`, and
 * its constructor signature is fixed `(Context, WorkerParameters)`. We
 * grab dependencies from `GlobalContext.get()` once the worker actually
 * runs - by then `KFileSyncApplication.onCreate` has already started Koin.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val koin = runCatching { GlobalContext.get() }.getOrElse {
            Napier.w("SyncWorker: Koin not initialised yet; deferring this run")
            return Result.retry()
        }

        val shareRepository: ShareRepository = koin.get()
        val syncService: SyncAppService = koin.get()
        val tombstones: TombstoneCleanupService = koin.get()
        val policyProvider: SyncPolicyProvider = koin.get()
        val batteryMonitor: BatteryMonitor = koin.get()
        val networkMonitor: NetworkMonitor = koin.get()
        val lifecycleMonitor: AppLifecycleMonitor = koin.get()

        return try {
            // T5.1: re-check policy at runtime. WorkManager's constraint
            // machinery already gates Wi-Fi, but charging / battery / app-
            // foreground are evaluated here.
            val policy = policyProvider.current()
            val ctx = buildContext(batteryMonitor, networkMonitor, lifecycleMonitor)
            if (!policy.shouldSync(ctx)) {
                Napier.d(
                    "SyncWorker: policy ${policy.id.wire} declined " +
                            "(wifi=${ctx.isWifi} charging=${ctx.isCharging} " +
                            "battery=${ctx.batteryLevel} fg=${ctx.isAppInForeground})"
                )
                return Result.success()
            }

            // Best-effort tombstone purge runs first - cheap, doesn't depend
            // on peers being reachable, and the resulting trimmed index makes
            // the index-exchange step lighter for everyone.
            runCatching { tombstones.sweepNow() }
                .onFailure { Napier.w("SyncWorker: tombstone sweep failed: ${it.message}") }

            val active = shareRepository.findAll().filter { it.status == ShareStatus.Active }
            if (active.isEmpty()) {
                Napier.d("SyncWorker: no active shares, nothing to do")
                return Result.success()
            }

            var anyFailed = false
            for (share in active) {
                runCatching { syncService.syncShare(share.id) }
                    .onFailure {
                        anyFailed = true
                        Napier.w("SyncWorker: syncShare(${share.id.value.take(8)}) failed: ${it.message}")
                    }
            }

            if (anyFailed && runAttemptCount < MAX_RETRIES) {
                Napier.i("SyncWorker: at least one share failed, retrying (attempt $runAttemptCount/$MAX_RETRIES)")
                Result.retry()
            } else {
                Result.success()
            }
        } catch (t: Throwable) {
            Napier.w("SyncWorker: unexpected failure: ${t.message}", t)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    /**
     * Single-shot snapshot of the three monitors. We deliberately avoid
     * subscribing to the observable flows here - the worker has a finite
     * budget and the policy decision needs to be made *now*. The monitors
     * cache their last reading, so this is a cheap call.
     */
    private suspend fun buildContext(
        battery: BatteryMonitor,
        network: NetworkMonitor,
        lifecycle: AppLifecycleMonitor
    ): SyncContext {
        val bat = runCatching { battery.snapshot() }.getOrNull()
        val net = runCatching { network.snapshot() }.getOrNull()
        val fg = runCatching { lifecycle.isForeground() }.getOrDefault(false)
        return SyncContext(
            isWifi = net?.isWifi ?: false,
            isCharging = bat?.isCharging ?: false,
            batteryLevel = bat?.levelFraction ?: SyncContext.BATTERY_UNKNOWN,
            isAppInForeground = fg
        )
    }

    companion object {
        private const val UNIQUE_NAME = "kfilesync.periodic-sync"
        private const val MAX_RETRIES = 3

        /**
         * Enqueue (or replace) the periodic sync request.
         *
         * Uses `ExistingPeriodicWorkPolicy.UPDATE` so a settings change to the
         * sync interval takes effect on the next call - calling [schedule]
         * with a different `intervalMinutes` replaces the schedule cleanly.
         *
         * Default 15-minute interval matches `DefaultSyncPolicy.syncInterval()`.
         * If the user picks Aggressive (5 min) or ChargingOnly (30 min), the
         * caller passes the appropriate value here.
         */
        fun schedule(context: Context, intervalMinutes: Long = 15L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Napier.i("SyncWorker scheduled every $intervalMinutes minutes on Wi-Fi")
        }

        /** Cancel the periodic work - used by tests + future settings toggle. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}