package com.kfilesync.mobile.platform

import com.kfilesync.mobile.application.service.ShareAppService
import com.kfilesync.mobile.application.service.StorageMaintenanceService
import com.kfilesync.mobile.application.service.SyncAppService
import com.kfilesync.mobile.application.service.TombstoneCleanupService
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.port.BatteryMonitor
import com.kfilesync.mobile.domain.port.NetworkMonitor
import com.kfilesync.mobile.domain.service.SyncContext
import com.kfilesync.mobile.domain.service.SyncPolicy
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * iOS background-sync orchestrator (T5.1, finally lands in Phase 5).
 *
 * Two task identifiers (declared in `iosApp/iosApp/Info.plist` under
 * `BGTaskSchedulerPermittedIdentifiers`):
 *
 * - `com.kfilesync.mobile.sync.refresh` - `BGAppRefreshTask`. Apple
 * schedules these every ~15 minutes when conditions are favourable
 * (Wi-Fi, charging, learned usage patterns). Used for the periodic
 * incremental sync loop.
 *
 * - `com.kfilesync.mobile.sync.process` - `BGProcessingTask`. Apple
 * grants ~minutes-long execution windows during charging, ideal for
 * storage maintenance + tombstone purges.
 *
 * **Important**: `BGTaskScheduler.shared.register` must be called *before*
 * `application(_:didFinishLaunchingWithOptions:)` returns - Apple checks
 * the registered identifier set at launch and refuses any
 * `BGTaskSchedulerPermittedIdentifiers` whose handler wasn't registered
 * in time. We call [registerTasks] from `iosBootstrap()` to satisfy this.
 *
 * Submission timing: the OS rate-limits the *user-visible* execution
 * window, but accepts unlimited submissions. We call [scheduleNext] from
 * the task handler completion path (so the next window is queued the
 * moment we finish a refresh).
 *
 * Why the two-tier task structure: a refresh task has a hard ~30 s wall
 * clock budget - too tight for a full sync of a large share. We use it
 * to *trigger* incremental sync only (no storage maintenance, no rehash).
 * The processing task gets minutes; it's where heavy maintenance lives.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundSync(
    private val syncService: SyncAppService,
    private val shareService: ShareAppService,
    private val tombstones: TombstoneCleanupService,
    private val storage: StorageMaintenanceService,
    private val syncPolicy: () -> SyncPolicy,
    private val networkMonitor: NetworkMonitor,
    private val batteryMonitor: BatteryMonitor
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Call once during app launch - before `IosApp.init` returns. */
    fun registerTasks() {
        runCatching {
            BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
                identifier = TASK_REFRESH,
                usingQueue = null
            ) { task ->
                handleRefreshTask(task as BGAppRefreshTask)
            }
        }.onFailure { Napier.w("BGTaskScheduler.register(refresh) failed: ${it.message}") }

        runCatching {
            BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
                identifier = TASK_PROCESS,
                usingQueue = null
            ) { task ->
                handleProcessingTask(task as BGProcessingTask)
            }
        }.onFailure { Napier.w("BGTaskScheduler.register(process) failed: ${it.message}") }

        Napier.i("IosBackgroundSync: registered $TASK_REFRESH, $TASK_PROCESS")
    }

    /** Queue the next refresh window. Safe to call repeatedly. */
    fun scheduleNext() {
        val interval = runCatching { syncPolicy().syncInterval().inWholeSeconds.toDouble() }
            .getOrDefault(15.0 * 60.0)
        runCatching {
            val req = BGAppRefreshTaskRequest(identifier = TASK_REFRESH)
            req.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(interval)
            BGTaskScheduler.sharedScheduler.submitTaskRequest(req, error = null)
        }.onFailure { Napier.w("submitTaskRequest(refresh) failed: ${it.message}") }

        // Processing task: queue one once per day, when charging.
        runCatching {
            val req = BGProcessingTaskRequest(identifier = TASK_PROCESS)
            req.requiresNetworkConnectivity = false
            req.requiresExternalPower = true
            req.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(6.0 * 60.0 * 60.0)
            BGTaskScheduler.sharedScheduler.submitTaskRequest(req, error = null)
        }.onFailure { Napier.w("submitTaskRequest(process) failed: ${it.message}") }
    }

    // ------------------ task handlers ------------------

    private fun handleRefreshTask(task: BGAppRefreshTask) {
        // Apple's API: set an expirationHandler that cancels the work if
        // the OS yanks our budget, then schedule the next request, then
        // finally call setTaskCompletedWithSuccess(true) or false.
        val workJob: Job = scope.launch {
            try {
                if (!shouldSyncNow()) {
                    Napier.d("BGAppRefreshTask: policy declined sync this cycle")
                    return@launch
                }
                val activeShares = runCatching { shareService.observeShares() }
                    .getOrNull()
                    ?: return@launch

                // We can't suspend on the flow forever; pull the latest snapshot
                // via observeShares()'s replay (it's a StateFlow under the hood).
                val snapshot = activeShares.tryFirstOrEmpty()
                for (row in snapshot.filter { it.status == ShareStatus.Active }) {
                    runCatching { syncService.syncShare(row.shareId) }
                        .onFailure { Napier.w("BG sync failed for ${row.shareId.value.take(8)}: ${it.message}") }
                }
            } finally {
                scheduleNext() // queue the next window before reporting completion
                task.setTaskCompletedWithSuccess(true)
            }
        }
        task.expirationHandler = {
            Napier.w("BGAppRefreshTask: expirationHandler fired; cancelling")
            workJob.cancel()
            task.setTaskCompletedWithSuccess(false)
        }
    }

    private fun handleProcessingTask(task: BGProcessingTask) {
        val workJob: Job = scope.launch {
            try {
                runCatching { tombstones.sweepNow() }
                    .onFailure { Napier.w("BG tombstone sweep failed: ${it.message}") }
                runCatching { storage.sweep() }
                    .onFailure { Napier.w("BG storage sweep failed: ${it.message}") }
            } finally {
                scheduleNext()
                task.setTaskCompletedWithSuccess(true)
            }
        }
        task.expirationHandler = {
            Napier.w("BGProcessingTask: expirationHandler fired; cancelling")
            workJob.cancel()
            task.setTaskCompletedWithSuccess(false)
        }
    }

    private suspend fun shouldSyncNow(): Boolean {
        val net = runCatching { networkMonitor.snapshot() }.getOrNull() ?: return false
        val bat = runCatching { batteryMonitor.snapshot() }.getOrNull() ?: return false
        val ctx = SyncContext(
            isWifi = net.isWifi,
            isCharging = bat.isCharging,
            batteryLevel = bat.levelFraction,
            isAppInForeground = false // BG tasks always run in background
        )
        return syncPolicy().shouldSync(ctx)
    }

    companion object {
        const val TASK_REFRESH: String = "com.kfilesync.mobile.sync.refresh"
        const val TASK_PROCESS: String = "com.kfilesync.mobile.sync.process"
    }

    /**
     * Pull the most recent emission off a Flow without suspending forever.
     * Used in the BGTask handlers where we have a strict wall-clock budget
     * and can't `collect { }`.
     */
    private suspend fun <T> kotlinx.coroutines.flow.Flow<List<T>>.tryFirstOrEmpty(): List<T> {
        var result: List<T> = emptyList()
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(500L) {
                this@tryFirstOrEmpty.collect {
                    result = it
                    throw kotlinx.coroutines.CancellationException("got first")
                }
            }
        }
        return result
    }
}