package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.domain.port.DirectoryScanner
import com.kfilesync.mobile.domain.port.FileSource
import com.kfilesync.mobile.domain.service.IgnoreSpec
import io.github.aakira.napier.Napier

/**
 * Reads a share's `.syncignore` file and composes it with mobile defaults
 * (Phase 5 carry-over of Phase 3 T3.4).
 *
 * Phase 4 wired every share to `IgnoreSpec.withMobileDefaults()` (defaults
 * only) because the indexer didn't yet have a way to read share-root
 * files. Phase 5 closes that gap:
 *
 * 1. [DirectoryScanner.resolve] locates the `.syncignore` entry inside
 * the share root, returning a platform-opaque locator.
 * 2. [FileSource.readWhole] yields the UTF-8 bytes.
 * 3. [IgnoreSpec.withMobileDefaults] composes user rules after defaults
 * (gitignore precedence - user can `!build/` to re-include defaults).
 *
 * Cache: the resolver is called once per sync session (the indexer factory
 * closure in `SharedModule` is constructed per `syncShare()` call), so we
 * don't memoise here. If profiling later shows `.syncignore` reads on the
 * hot path, hoist a `Map<ShareId, IgnoreSpec>` here with mtime-keyed
 * invalidation.
 *
 * Failure modes:
 * - `.syncignore` doesn't exist -> return defaults-only spec.
 * - File read fails (permission revoked, IO error) -> log warn, return
 * defaults-only spec. We deliberately don't propagate the error: a
 * broken ignore file shouldn't break sync entirely.
 */
class SyncIgnoreReader(
    private val directoryScanner: DirectoryScanner,
    private val fileSource: FileSource
) {

    suspend fun loadForShare(rootLocator: String): IgnoreSpec {
        if (rootLocator.isBlank()) return IgnoreSpec.withMobileDefaults()

        val entry = runCatching { directoryScanner.resolve(rootLocator, IGNORE_FILE_NAME) }
            .getOrNull()
            ?: return IgnoreSpec.withMobileDefaults()

        if (entry.isDirectory) {
            // Someone named a directory ".syncignore" - invalid; fall back.
            return IgnoreSpec.withMobileDefaults()
        }

        // Cap on read size - a syncignore file with more than 256 KiB of
        // glob rules is almost certainly malicious / corrupted. Real-world
        // files are < 4 KiB.
        if (entry.sizeBytes > MAX_IGNORE_BYTES) {
            Napier.w("syncignore: .syncignore at $rootLocator too large (${entry.sizeBytes} B); using defaults only")
            return IgnoreSpec.withMobileDefaults()
        }

        val bytes = runCatching { fileSource.readWhole(entry.locator) }
            .onFailure { Napier.w("syncignore: read failed for $rootLocator/.syncignore: ${it.message}") }
            .getOrNull()
            ?: return IgnoreSpec.withMobileDefaults()

        runCatching { fileSource.close(entry.locator) }

        val text = bytes.decodeToString()
        return IgnoreSpec.withMobileDefaults(text)
    }

    companion object {
        const val IGNORE_FILE_NAME: String = ".syncignore"
        const val MAX_IGNORE_BYTES: Long = 256L * 1024L
    }
}