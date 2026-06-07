package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.port.FileEvent
import com.kfilesync.mobile.domain.port.FileWatcher
import com.kfilesync.mobile.domain.port.WatchHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps a platform [FileWatcher] with 500ms per-path debouncing (T4.2).
 *
 * File-system events tend to fire in storms when a user saves a file
 * (e.g. an editor's "atomic save" writes to a temp file and renames it,
 * producing Create + Modify + Delete events on the directory in rapid
 * succession). The sync engine doesn't care about every individual edge:
 * it only needs to know "something changed under `path`" so it can
 * trigger an incremental scan.
 *
 * Strategy: every inbound event for a watched path resets THAT path's
 * 500ms timer (not every path's). When the timer expires without further
 * events, we fire one synthetic [FileEvent.Modified] callback to the
 * consumer. This collapses a save-storm into a single scan trigger
 * per share root.
 *
 * Issue #19 fix: the previous implementation cancelled every pending
 * timer on every inbound event (regardless of which path it came from),
 * so a save-storm in share A would silently swallow a real change in
 * share B. We now key the pending-job map by the watched path itself,
 * so timer-cancellation only touches the matching path.
 *
 * 'unwatch' is now keyed by both the platform [WatchHandle] (for the
 * delegate) and the stored path (for our pending-jobs map). The previous
 * code used the platform handle for both, but our map key was a synthetic
 * 'WatchHandle(path)' \u2014 so cancellation silently missed.
 */
class DebouncedFileWatcher(
    private val delegate: FileWatcher,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 500L,
    private val onError: ((String) -> Unit)? = null
) : FileWatcher {

    /** Maps the watched path \u2192 the currently-pending fire job. */
    private val pendingByPath = mutableMapOf<String, Job>()

    /** Maps the platform handle \u2192 the path it watches, so unwatch() can clean up. */
    private val pathByHandle = mutableMapOf<WatchHandle, String>()

    private val lock = Mutex()

    override suspend fun watch(path: String, onChange: (FileEvent) -> Unit): WatchHandle {
        val handle = delegate.watch(path) { _ ->
            // Reset the timer for THIS path only.
            scope.launch {
                val freshJob = scope.launch {
                    delay(debounceMillis)
                    runCatching { onChange(FileEvent.Modified(path)) }
                        .onFailure { onError?.invoke("debounced onChange threw: ${it.message}") }
                }
                lock.withLock {
                    pendingByPath.remove(path)?.cancel()
                    pendingByPath[path] = freshJob
                }
            }
        }
        lock.withLock { pathByHandle[handle] = path }
        return handle
    }

    override suspend fun unwatch(handle: WatchHandle) {
        lock.withLock {
            val path = pathByHandle.remove(handle)
            if (path != null) {
                pendingByPath.remove(path)?.cancel()
            }
        }
        delegate.unwatch(handle)
    }
}