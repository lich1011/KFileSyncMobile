package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.ConflictResolution
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.SyncConflict
import com.kfilesync.mobile.domain.model.VersionVector
import kotlin.time.Instant

/**
 * iOS implementation: hand-written formula, kept temporarily because this
 * machine has no full Xcode to build kfilesync-core's iOS UniFFI/XCFramework
 * artifact (mirrors [com.kfilesync.mobile.domain.service.SizeBasedChunking]'s
 * iOS fallback in Sprint 1).
 *
 * **KeepBoth` orientation intentionally differs from Android** (see
 * `ConflictResolver.android.kt` / `applyResolution`'s Rust doc comment):
 * here `remote` keeps the canonical path and `local`'s content is moved to
 * the conflict copy, stamped with `now` / `me` - the original mobile design,
 * predating the migration decision to standardize on core's orientation
 * (local keeps the path, remote's content moves to the copy, stamped with
 * the *losing* entry's own `modifiedAt`). This divergence is a known,
 * accepted gap until iOS migrates too - not a bug.
 */
actual class ConflictResolver actual constructor() {

    actual fun resolve(
        conflict: SyncConflict,
        resolution: ConflictResolution,
        me: DeviceId,
        now: Instant
    ): Resolution {
        val local = conflict.local
        val remote = conflict.remote
        return when (resolution) {
            ConflictResolution.KeepLocal -> {
                // Local wins. Merge the remote vector so we record having seen
                // their version; the next push will be a clean ancestor relation.
                val merged = local.copy(
                    versionVector = local.versionVector.merge(remote.versionVector),
                    updatedAt = now
                )
                Resolution(primary = merged, conflictCopy = null)
            }
            ConflictResolution.KeepRemote -> {
                // Remote wins; the on-disk file is overwritten by inbound chunks.
                val adopted = local.copy(
                    size = remote.size,
                    sha256 = remote.sha256,
                    blocks = remote.blocks,
                    modifiedAt = remote.modifiedAt,
                    modifiedBy = remote.modifiedBy,
                    deleted = remote.deleted,
                    deletedAt = remote.deletedAt,
                    versionVector = local.versionVector.merge(remote.versionVector),
                    updatedAt = now
                )
                Resolution(primary = adopted, conflictCopy = null)
            }
            ConflictResolution.KeepBoth -> {
                // Remote takes the canonical path; local is moved to a conflict-copy
                // path with our deviceId stamped in.
                val copyPath = conflictCopyName(local.path, me, now)
                val adoptedPrimary = local.copy(
                    size = remote.size,
                    sha256 = remote.sha256,
                    blocks = remote.blocks,
                    modifiedAt = remote.modifiedAt,
                    modifiedBy = remote.modifiedBy,
                    deleted = remote.deleted,
                    deletedAt = remote.deletedAt,
                    versionVector = local.versionVector.merge(remote.versionVector),
                    updatedAt = now
                )
                // The conflict copy carries the *local* payload but at a new
                // path, with its own fresh version vector (the local user
                // effectively "created" a new file by retaining their copy).
                val copyEntry = FileEntry(
                    shareId = local.shareId,
                    path = copyPath,
                    entryType = local.entryType,
                    size = local.size,
                    modifiedAt = now,
                    modifiedBy = me,
                    versionVector = VersionVector(mapOf(me to 1L)),
                    sha256 = local.sha256,
                    blocks = local.blocks,
                    deleted = false,
                    deletedAt = null,
                    updatedAt = now
                )
                Resolution(primary = adoptedPrimary, conflictCopy = copyEntry)
            }
        }
    }

    /**
     * Generate the conflict-copy filename for a [path] losing on a device
     * with [losingDeviceId] at the given [conflictAt] instant.
     *
     * Conflict-copy filename format (matches the desktop client verbatim):
     *
     * `<basename>.sync-conflict-<YYYYMMDD>-<HHMMSS>-<deviceShortHex>.<ext>`
     */
    actual fun conflictCopyName(path: String, losingDeviceId: DeviceId, conflictAt: Instant): String {
        val (basename, ext) = splitExtension(path)
        val stamp = formatStamp(conflictAt)
        val shortDev = losingDeviceId.value.take(8).lowercase()
        val suffix = ".sync-conflict-$stamp-$shortDev"
        return if (ext.isEmpty()) "$basename$suffix" else "$basename$suffix.$ext"
    }

    // ---------- helpers ----------

    /**
     * Split [path] into (basename, ext) using the *last* dot in the leaf
     * filename. Returns ext = "" if the leaf has no dot or starts with a dot
     * (dotfiles like `.gitignore` have no extension).
     */
    private fun splitExtension(path: String): Pair<String, String> {
        val lastSlash = path.lastIndexOf('/')
        val leafStart = lastSlash + 1
        val leaf = path.substring(leafStart)
        val dot = leaf.lastIndexOf('.')
        if (dot <= 0) return Pair(path, "") // no dot, or leading dot only (dotfile)
        return Pair(path.substring(0, leafStart + dot), leaf.substring(dot + 1))
    }

    /**
     * Format an [Instant] as `YYYYMMDD-HHMMSS` in UTC (deterministic across
     * peers). Implemented by hand rather than via `kotlinx-datetime` because
     * the mobile project doesn't depend on it; pure arithmetic is sufficient.
     */
    private fun formatStamp(instant: Instant): String {
        val totalMs = instant.toEpochMilliseconds()
        // Days since epoch - use pure-Kotlin floor division so this works in
        // commonMain (Math.floorDiv is JVM-only).
        val msPerDay = 86_400_000L
        val daysFromEpoch = floorDivLong(totalMs, msPerDay)
        val msInDay = floorModLong(totalMs, msPerDay)
        val secOfDay = (msInDay / 1000L).toInt()
        val hours = secOfDay / 3600
        val minutes = (secOfDay % 3600) / 60
        val seconds = secOfDay % 60

        val (year, month, day) = civilFromDays(daysFromEpoch)

        return buildString {
            append(year.toString().padStart(4, '0'))
            append(month.toString().padStart(2, '0'))
            append(day.toString().padStart(2, '0'))
            append('-')
            append(hours.toString().padStart(2, '0'))
            append(minutes.toString().padStart(2, '0'))
            append(seconds.toString().padStart(2, '0'))
        }
    }

    /** Pure-Kotlin floor division on Long. */
    private fun floorDivLong(x: Long, y: Long): Long {
        var q = x / y
        if ((x xor y) < 0L && q * y != x) q -= 1L
        return q
    }

    /** Pure-Kotlin floor modulo on Long. */
    private fun floorModLong(x: Long, y: Long): Long = x - floorDivLong(x, y) * y

    /**
     * Days-since-Unix-epoch -> (year, month, day) (Gregorian).
     * Algorithm from Howard Hinnant's date library - handles years 1970+
     * correctly without leap-year edge cases.
     */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val shifted = days + 719468L // shift so era origin is March 1, year 0
        val era = if (shifted >= 0) shifted / 146097L else (shifted - 146096L) / 146097L
        val doe = (shifted - era * 146097L).toInt()        // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
        val y = yoe + (era * 400L).toInt()
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)  // [0, 365]
        val mp = (5 * doy + 2) / 153                       // [0, 11]
        val d = doy - (153 * mp + 2) / 5 + 1               // [1, 31]
        val m = if (mp < 10) mp + 3 else mp - 9            // [1, 12]
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }
}