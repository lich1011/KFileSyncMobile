package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.SyncConflict

/**
 * Pure function domain service: deterministic conflict resolution.
 * Phase 4 (T4.4) implements: conflict-copy naming
 * `<name>.sync-conflict-<YYYYMMDD>-<HHMMSS>-<device_id_short>.<ext>`
 *
 * The algorithm must be 100% branch-covered and identical to the desktop.
 */
class ConflictResolver {
    fun resolve(local: FileEntry, remote: FileEntry): SyncConflict {
        TODO("Phase 4 – port the desktop ConflictResolver verbatim into commonMain.")
    }
}