package com.kfilesync.mobile.domain.port

/** Opaque handle returned by FileWatcher.watch - passed back to unwatch. */
@kotlin.jvm.JvmInline
value class WatchHandle(val id: String)

sealed class FileEvent {
    data class Created(val path: String) : FileEvent()
    data class Modified(val path: String) : FileEvent()
    data class Deleted(val path: String) : FileEvent()
}

/**
 * Platform file watcher. Android: FileObserver; iOS: DispatchSource.
 * Phase 4 (T4.2) wires in debouncing + periodic full-scan fallback.
 */
interface FileWatcher {
    suspend fun watch(path: String, onChange: (FileEvent) -> Unit): WatchHandle
    suspend fun unwatch(handle: WatchHandle)
}