package com.kfilesync.mobile.domain.service

/**
 * `.syncignore` parser + matcher (design doc §14 Phase 3 T3.4).
 *
 * Wire-compatible with the desktop client's `.syncignore` syntax - same
 * patterns, same precedence rules:
 *
 * - Lines starting with '#' are comments (ignored).
 * - Blank lines are ignored.
 * - Lines starting with '!' are *negations* (re-include a previously
 * ignored path). Negations apply only to files; they cannot un-ignore
 * a directory once its parent was ignored (matches gitignore semantics).
 * - Glob patterns use `*` (any chars except `/`), `**` (any chars
 * including `/`), `?` (single char), `[abc]` (char class).
 * - A trailing `/` matches directories only.
 * - Leading `/` anchors to the share root; otherwise the pattern may
 * match at any depth.
 *
 * On top of the user's `.syncignore` file, mobile applies an extra layer
 * of **default ignores** for platform-specific cruft that should never
 * leave the device - see [DEFAULT_MOBILE_IGNORES].
 *
 * This module is pure (no I/O); the file reader lives in Phase 4 alongside
 * the file-watcher infrastructure.
 */
expect class IgnoreSpec {

    /**
     * 'true' iff [path] (relative to the share root, forward-slash separated)
     * should be skipped during indexing/sync.
     */
    fun shouldIgnore(path: String, isDirectory: Boolean = false): Boolean

    companion object {

        fun withMobileDefaults(userText: String = ""): IgnoreSpec 
       
    }
}