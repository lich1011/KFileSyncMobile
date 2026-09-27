package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.ConflictResolution
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.SyncConflict
import kotlin.time.Instant

/**
 * Deterministic conflict-copy naming + resolution (design doc §14 Phase 4 T4.4).
 *
 * Conflict-copy filename format (matches the desktop client verbatim):
 *
 * `<basename>.sync-conflict-<YYYYMMDD>-<HHMMSS>-<deviceShortHex>.<ext>`
 *
 * Examples:
 * - `report.docx`      -> `report.sync-conflict-20260524-143015-a1b2c3d4.docx`
 * - `notes`            -> `notes.sync-conflict-20260524-143015-a1b2c3d4`
 * - `archive.tar.gz`   -> `archive.tar.sync-conflict-20260524-143015-a1b2c3d4.gz`
 * (only the *last* dot is treated as the extension separator, matching
 * the desktop's behaviour; `archive.tar.gz` keeps `.gz` as the ext.)
 *
 * Determinism rationale: two devices in the same conflict will independently
 * call [conflictCopyName] with the same `(path, losingSideDeviceId, conflictAt)`
 * inputs and produce the same filename - so the conflict copy "appears" on
 * both sides without needing a separate negotiation round.
 *
 * The resolver is pure; the application-layer caller picks the
 * [ConflictResolution] strategy (KeepLocal / KeepRemote / KeepBoth) and then
 * either renames the on-disk loser (KeepBoth) or just adopts the winner's
 * version vector (KeepLocal / KeepRemote).
 */
expect class ConflictResolver() {

    /**
     * Apply [resolution] to the conflict and return the new [FileEntry] state
     * that should replace [conflict.local].
     *
     * - KeepLocal:  local wins, but merge remote's vector so we record having
     * seen their version. On-disk file is unchanged.
     * - KeepRemote: remote wins; local adopts remote's payload + vector.
     * On-disk file is overwritten by the inbound chunks
     * (Phase 4 T4.5 actually moves the bytes; this returns
     * the post-write index state).
     * - KeepBoth:   remote wins for the original path; local is renamed
     * to a conflict copy. Caller is responsible for renaming
     * the on-disk file via the returned [Resolution.conflictCopy].
     */
    fun resolve(
        conflict: SyncConflict,
        resolution: ConflictResolution,
        me: DeviceId,
        now: Instant
    ): Resolution

    fun conflictCopyName(path: String, losingDeviceId: DeviceId, conflictAt: Instant): String 
}

/**
    * Resolution result. [primary] is the new state for the original path.
    * [conflictCopy] is non-null only for `KeepBoth` - the application layer
    * must persist this entry and physically rename the on-disk file.
    */
data class Resolution(
    val primary: FileEntry,
    val conflictCopy: FileEntry?
)