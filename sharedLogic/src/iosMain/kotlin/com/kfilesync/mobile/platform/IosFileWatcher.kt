package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.FileEvent
import com.kfilesync.mobile.domain.port.FileWatcher
import com.kfilesync.mobile.domain.port.WatchHandle
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_source_cancel
import platform.darwin.dispatch_source_create
import platform.darwin.dispatch_source_set_event_handler
import platform.darwin.dispatch_source_t
import platform.darwin.DISPATCH_SOURCE_TYPE_VNODE
import platform.posix.O_EVTONLY
import platform.posix.close
import platform.posix.open

/**
 * iOS 'FileWatcher' (T4.2 - full implementation finally lands in Phase 5).
 *
 * Backed by 'dispatch_source' of type 'DISPATCH_SOURCE_TYPE_VNODE', which
 * is Apple's recommended primitive for watching a *single* file descriptor.
 * The DispatchSource fires whenever the underlying inode changes - write,
 * extend, attribute change, delete, rename, link, revoke.
 *
 * Limitations:
 * - VNODE only watches the file descriptor we open, not children.
 * Recursive monitoring requires walking the tree and opening a source
 * per directory + per file. We deliberately keep that responsibility
 * out of the FileWatcher port; [DebouncedFileWatcher] just kicks off
 * a full rescan on any event, so we only need to watch the *root*.
 * - 'O_EVTONLY' is a Darwin extension that opens the FD without trying
 * to read the file. Critical for permission-restricted paths.
 * - The source must be **resumed** with 'dispatch_resume' before it
 * delivers events - that's a one-shot suspend/resume API; we resume
 * once at the end of [watch].
 *
 * Cancellation: 'dispatch_source_cancel' schedules the source for
 * teardown. We also close the FD inside the cancel handler so the OS
 * releases the inode lock cleanly.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileWatcher : FileWatcher {

    private data class Armed(val source: dispatch_source_t, val fd: Int)

    private val armed = mutableMapOf<String, Armed>()

    override suspend fun watch(path: String, onChange: (FileEvent) -> Unit): WatchHandle =
        withContext(Dispatchers.Default) {
            val handle = WatchHandle(path)
            if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
                Napier.w("IosFileWatcher: path does not exist: $path")
                return@withContext handle
            }
            val fd = open(path, O_EVTONLY)
            if (fd < 0) {
                Napier.w("IosFileWatcher: open(O_EVTONLY) failed for $path")
                return@withContext handle
            }
            // Mask: watch the four high-traffic transitions. ATTRIB covers
            // mtime updates which are what we mostly use for change detection.
            val mask: ULong =
                VNODE_WRITE or VNODE_EXTEND or VNODE_DELETE or VNODE_RENAME or VNODE_ATTRIB
            val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
            val source = dispatch_source_create(
                type = DISPATCH_SOURCE_TYPE_VNODE,
                handle = fd.toULong(),
                mask = mask,
                queue = queue
            ) ?: run {
                close(fd)
                Napier.w("IosFileWatcher: dispatch_source_create returned null for $path")
                return@withContext handle
            }

            dispatch_source_set_event_handler(source) {
                // We don't bother decoding the bit-flag set - the
                // DebouncedFileWatcher rescans the whole tree on any event,
                // so a single Modified is enough to drive that.
                runCatching { onChange(FileEvent.Modified(path)) }
                    .onFailure { Napier.w("IosFileWatcher onChange threw: ${it.message}") }
            }

            // Resume to start delivering events. Without this the source is
            // inert. Note: there's no dispatch_source_set_cancel_handler bridge
            // available here in K/N cinterop without an Obj-C helper, so we
            // close(fd) explicitly in unwatch() instead.
            platform.darwin.dispatch_resume(source)
            armed[handle.id] = Armed(source, fd)
            Napier.d("IosFileWatcher: armed dispatch_source for $path (fd=$fd)")
            handle
        }

    override suspend fun unwatch(handle: WatchHandle) = withContext(Dispatchers.Default) {
        val entry = armed.remove(handle.id) ?: return@withContext
        runCatching { dispatch_source_cancel(entry.source) }
            .onFailure { Napier.w("dispatch_source_cancel failed: ${it.message}") }
        runCatching { close(entry.fd) }
        Napier.d("IosFileWatcher: cancelled watch for ${handle.id}")
        Unit
    }

    companion object {
        // From <sys/event.h> via Darwin headers. K/N cinterop exposes these
        // as constants on platform.darwin in recent versions; we hard-code
        // here for clarity (and to avoid a "is this constant exposed?" ABI
        // game with future Kotlin versions).

        private const val VNODE_DELETE: ULong = 0x0001u
        private const val VNODE_WRITE: ULong = 0x0002u
        private const val VNODE_EXTEND: ULong = 0x0004u
        private const val VNODE_ATTRIB: ULong = 0x0008u
        private const val VNODE_RENAME: ULong = 0x0010u
    }
}