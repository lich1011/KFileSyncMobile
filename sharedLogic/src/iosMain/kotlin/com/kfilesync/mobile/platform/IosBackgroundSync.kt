package com.kfilesync.mobile.platform

/**
 * Background sync orchestrator backed by iOS `BGTaskScheduler`.
 *
 * Phase 5 (T5.1) registers `com.kfilesync.mobile.sync.refresh` (~15 min interval)
 * and `com.kfilesync.mobile.sync.process` (longer processing window) at
 * `application(_:didFinishLaunchingWithOptions:)` time. Both identifiers are
 * declared in iosApp/Info.plist's `BGTaskSchedulerPermittedIdentifiers`.
 */
class IosBackgroundSync {

    fun registerTasks(): Unit = TODO("Phase 5 T5.1")

    fun scheduleNext(): Unit = TODO("Phase 5 T5.1")
}