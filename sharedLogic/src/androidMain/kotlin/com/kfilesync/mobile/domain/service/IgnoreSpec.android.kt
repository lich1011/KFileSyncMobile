package com.kfilesync.mobile.domain.service

import io.github.aakira.napier.Napier

/**
 * Sprint 6: delegates to core's `uniffi.kfilesync_core.IgnoreSpec` (a UniFFI
 * `Object` holding a native pointer, `Disposable`/`AutoCloseable`). We don't
 * explicitly `close()` it — callers construct a fresh spec per `syncShare()`
 * call (see `SyncIgnoreReader`), matching the lifecycle it already had as a
 * plain data class; the UniFFI Cleaner reclaims the native side on GC.
 */
actual class IgnoreSpec private constructor(
    private val native: uniffi.kfilesync_core.IgnoreSpec
) {
    actual fun shouldIgnore(path: String, isDirectory: Boolean): Boolean =
        native.isIgnored(path, isDirectory)

    actual companion object {
        actual fun withMobileDefaults(userText: String): IgnoreSpec {
            val content = userText.ifBlank { null }
            return try {
                IgnoreSpec(buildNative(syncignoreContent = content))
            } catch (e: uniffi.kfilesync_core.IgnoreSpecException) {
                // Mirrors SyncIgnoreReader's existing "never throws, fall back
                // to defaults" contract — a malformed .syncignore shouldn't
                // break sync, it should just be ignored (pun intended).
                Napier.w(message = "invalid .syncignore rule, falling back to defaults only: ${e.message}")
                IgnoreSpec(buildNative(syncignoreContent = null))
            }
        }

        private fun buildNative(syncignoreContent: String?): uniffi.kfilesync_core.IgnoreSpec =
            uniffi.kfilesync_core.IgnoreSpec.build(
                shareRoot = "/", // core: this param currently has no effect on matching
                syncignoreContent = syncignoreContent,
                extraUserRules = emptyList(),
                isMobile = true
            )
    }
}