package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.FileEvent
import com.kfilesync.mobile.domain.port.FileWatcher
import com.kfilesync.mobile.domain.port.WatchHandle

/**
 * iOS [FileWatcher] adapter backed by `DispatchSource.makeFileSystemObjectSource`.
 *
 * Phase 4 (T4.2). Like the Android adapter it must debounce bursts of events
 * and run a periodic full-scan fallback because iOS only fires on the watched
 * file descriptor (not on every descendant - the orchestrator walks the tree
 * and arms a source per file).
 */
class IosDispatchSourceWatcher : FileWatcher {

    override suspend fun watch(path: String, onChange: (FileEvent) -> Unit): WatchHandle = TODO("Phase 4 T4.2")

    override suspend fun unwatch(handle: WatchHandle): Unit = TODO("Phase 4 T4.2")
}