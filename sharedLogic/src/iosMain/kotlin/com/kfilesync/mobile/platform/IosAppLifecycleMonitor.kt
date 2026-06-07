package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.AppLifecycleMonitor
import io.github.aakira.napier.Napier
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication

/**
 * iOS `AppLifecycleMonitor` (T5.1).
 *
 * Backed by `UIApplication.didBecomeActiveNotification` /
 * `willResignActiveNotification` - the same notifications Apple's own
 * `AppDelegate.applicationDidBecomeActive` listens to.
 *
 * - "Active" maps to our `foreground = true`.
 * - "Resign active" (includes incoming-call interruption, control center
 * pull-down, app switcher) maps to `foreground = false`. This is
 * slightly more aggressive than backgrounding - a quick control-center
 * flick will count as background - which is fine for our use case
 * (sync policy treats these brief moments the same way).
 *
 * The initial value (foreground when the app first launches) is delivered
 * on subscribe via `onStart` rather than waiting for the next transition.
 */
class IosAppLifecycleMonitor : AppLifecycleMonitor {

    private val foreground = atomic(true) // app starts in foreground

    override suspend fun isForeground(): Boolean = foreground.value

    override fun observe(): Flow<Boolean> = callbackFlow {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue

        val didBecomeActive = center.addObserverForName(
            name = "UIApplicationDidBecomeActiveNotification",
            `object` = null,
            queue = queue
        ) { _ ->
            foreground.value = true
            trySend(true)
        }

        val willResignActive = center.addObserverForName(
            name = "UIApplicationWillResignActiveNotification",
            `object` = null,
            queue = queue
        ) { _ ->
            foreground.value = false
            trySend(false)
        }

        awaitClose {
            runCatching {
                center.removeObserver(didBecomeActive)
                center.removeObserver(willResignActive)
            }.onFailure { Napier.w("AppLifecycleMonitor cleanup failed: ${it.message}") }
        }
    }
        .onStart { emit(foreground.value) }
        .distinctUntilChanged()
}