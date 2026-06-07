package com.kfilesync.mobile.platform

import android.os.FileObserver
import com.kfilesync.mobile.domain.port.FileEvent
import com.kfilesync.mobile.domain.port.FileWatcher
import com.kfilesync.mobile.domain.port.WatchHandle
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android `FileWatcher` (T4.2) backed by `android.os.FileObserver`.
 *
 * **Limitations** -- critical to understand:
 * - `FileObserver` only works for paths on the local filesystem (`/data/`,
 * `/sdcard/`, etc). It does **not** work for SAF tree URIs. Shares whose
 * `localPath` is a SAF tree URI (the common case on Android 11+) have
 * no file-watcher coverage -- we fall back to the periodic scan
 * scheduled by WorkManager (T4.7).
 * - `FileObserver` is *not* recursive on Android API < 29. We always
 * pass an empty list of paths (i.e. only watch the root) and rely on
 * [DebouncedFileWatcher] to trigger a full re-scan on any signal.
 *
 * Constructor choice: we use the legacy `(String, Int)` form (deprecated
 * in API 29 in favour of `(File, Int)`) so the build still works on the
 * project's `android-minSdkVersion = 26`. The new constructor adds no behaviour
 * we need, and the deprecation is non-blocking.
 *
 * The watcher is intentionally a thin facade -- it converts the raw event
 * mask into our [FileEvent] sum type and forwards. The sync engine treats
 * any event as "rescan needed" so the per-event semantics are unimportant.
 */
class AndroidFileWatcher : FileWatcher {

    private val observers = mutableMapOf<WatchHandle, FileObserver>()

    override suspend fun watch(path: String, onChange: (FileEvent) -> Unit): WatchHandle = withContext(Dispatchers.IO) {
        val handle = WatchHandle(path)
        // Only POSIX paths are supported by FileObserver. SAF tree URIs
        // (content://) are silently ignored here -- we return the handle
        // anyway so the caller's bookkeeping stays consistent.
        if (!path.startsWith("/")) {
            Napier.w("AndroidFileWatcher: ignoring non-filesystem path (probably SAF URI): $path")
            return@withContext handle
        }
        val file = File(path)
        if (!file.exists()) {
            Napier.w("AndroidFileWatcher: watched path does not exist: $path")
            return@withContext handle
        }

        @Suppress("DEPRECATION")
        val observer = object : FileObserver(path, ALL_EVENTS) {
            override fun onEvent(event: Int, eventPath: String?) {
                val full = if (eventPath == null) path else "$path/$eventPath"
                val fe = when {
                    (event and CREATE) != 0 -> FileEvent.Created(full)
                    (event and DELETE) != 0 -> FileEvent.Deleted(full)
                    else -> FileEvent.Modified(full)
                }
                runCatching { onChange(fe) }
                    .onFailure { Napier.w("AndroidFileWatcher onChange threw: ${it.message}") }
            }
        }
        observer.startWatching()
        observers[handle] = observer
        handle
    }

    override suspend fun unwatch(handle: WatchHandle) = withContext(Dispatchers.IO) {
        observers.remove(handle)?.stopWatching()
        Unit
    }

    companion object {
        // Combined event mask: CREATE, DELETE, MODIFY, MOVED_FROM, MOVED_TO.
        // We OR them all so a save-rename triggers at least one event.
        private const val ALL_EVENTS: Int =
            FileObserver.CREATE or
                    FileObserver.DELETE or
                    FileObserver.MODIFY or
                    FileObserver.MOVED_FROM or
                    FileObserver.MOVED_TO
    }
}