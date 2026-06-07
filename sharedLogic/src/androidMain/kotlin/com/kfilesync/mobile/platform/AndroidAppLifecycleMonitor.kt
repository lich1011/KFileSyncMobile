package com.kfilesync.mobile.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kfilesync.mobile.domain.port.AppLifecycleMonitor
import io.github.aakira.napier.Napier
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * Android `AppLifecycleMonitor` (T5.1) backed by [ProcessLifecycleOwner].
 *
 * `ProcessLifecycleOwner` tracks the *whole* app's foreground state - not
 * a single Activity. It's perfect for the sync-policy decision:
 * - `onStart` -> foreground (user opened any KFileSync activity).
 * - `onStop`  -> background (no activity is in the foreground; the
 * Process is still alive but the user isn't looking).
 *
 * The observer must be added on the main thread (`androidx.lifecycle`
 * contract). We post via `runCatching` so a wrong-thread invocation logs
 * but doesn't crash. Production code calls observe() from a coroutine
 * eventually dispatched to Main anyway (Koin viewModelScope etc).
 *
 * Note: requires `androidx.lifecycle:lifecycle-process` on the classpath
 * - we satisfy this via the existing `lifecycle-runtimeCompose`
 * transitive dependency declared in `gradle/libs.versions.toml`.
 */
class AndroidAppLifecycleMonitor : AppLifecycleMonitor {

    private val owner get() = ProcessLifecycleOwner.get()

    override suspend fun isForeground(): Boolean =
        // Lifecycle.State.STARTED or higher == app foreground.
        owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    override fun observe(): Flow<Boolean> = callbackFlow {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(o: LifecycleOwner) { trySend(true) }
            override fun onStop(o: LifecycleOwner) { trySend(false) }
        }

        runCatching { owner.lifecycle.addObserver(observer) }
            .onFailure { Napier.w("AppLifecycleMonitor register failed: ${it.message}") }

        awaitClose {
            runCatching { owner.lifecycle.removeObserver(observer) }
        }
    }
        .onStart { emit(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
        .distinctUntilChanged()
}