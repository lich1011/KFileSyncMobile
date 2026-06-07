package com.kfilesync.mobile.domain.model

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Version vector value object - Map<DeviceId, Long> used to determine
 * causality between file versions or detect conflicts (§6.1.2).
 *
 * Immutable; all mutating operations return a new instance.
 */
data class VersionVector(val entries: Map<DeviceId, Long> = emptyMap()) {

    /** True if this vector is causally before or equal to [other]. */
    fun isAncestorOf(other: VersionVector): Boolean =
        entries.all { (device, counter) ->
            (other.entries[device] ?: 0L) >= counter
        }

    /** True if neither vector is an ancestor of the other (concurrent edits). */
    fun conflictsWith(other: VersionVector): Boolean =
        !isAncestorOf(other) && !other.isAncestorOf(this)

    /** Returns a copy with [device]'s counter incremented by 1. */
    fun increment(device: DeviceId): VersionVector =
        copy(entries = entries + (device to ((entries[device] ?: 0L) + 1L)))

    /** Returns the per-key maximum of both vectors. */
    fun merge(other: VersionVector): VersionVector {
        val allKeys = entries.keys + other.entries.keys
        return VersionVector(
            allKeys.associateWith { key ->
                maxOf(entries[key] ?: 0L, other.entries[key] ?: 0L)
            }
        )
    }
}

data class BlockLocation(val shareId: ShareId, val path: String, val offset: Long, val size: Int)

@kotlin.jvm.JvmInline
value class ContentHash(val sha256Hex: String)

enum class EntryType { File, Directory }

/**
 * File index aggregate root (design doc §6.1.2 + Phase 4 T4.1).
 *
 * Represents one file (or directory) inside a shared folder. The [versionVector]
 * captures the per-device write history - used by [SyncPlanGenerator] to
 * decide pull / push / conflict on each entry, and by [ConflictResolver] to
 * name conflict copies deterministically.
 *
 * Pure domain methods:
 * - [withLocalEdit]: this device just wrote the file -> bump my counter in
 * the version vector, refresh content hash + size + mtime.
 * - [withRemoteUpdate]: an inbound sync delivered a newer version -> adopt
 * the remote vector verbatim (it already merged everyone's history).
 * - [markDeleted]: tombstone the entry. Kept for 30 days (cleanup query
 * in `FileEntry.sq`) so a re-creation later doesn't resurrect the old
 * version vector.
 * - [isAncestorOf] / [conflictsWith]: shortcut wrappers on [versionVector].
 */
data class FileEntry(
    val shareId: ShareId,
    val path: String,
    val entryType: EntryType = EntryType.File,
    val size: Long = 0L,
    val modifiedAt: Instant? = null,
    val modifiedBy: DeviceId? = null,
    val versionVector: VersionVector = VersionVector(),
    val sha256: ContentHash? = null,
    val blocks: List<String> = emptyList(), // BLAKE3 hex list (per-chunk)
    val deleted: Boolean = false,
    val deletedAt: Instant? = null,
    val updatedAt: Instant
) {

    /** Local user changed this file. Bumps the version vector for [me]. */
    fun withLocalEdit(
        me: DeviceId,
        newSize: Long,
        newSha256: ContentHash?,
        newBlocks: List<String>,
        now: Instant = Clock.System.now()
    ): FileEntry = copy(
        size = newSize,
        sha256 = newSha256,
        blocks = newBlocks,
        modifiedAt = now,
        modifiedBy = me,
        versionVector = versionVector.increment(me),
        deleted = false,
        deletedAt = null,
        updatedAt = now
    )

    /** Remote peer delivered a newer version - adopt their vector + payload. */
    fun withRemoteUpdate(remote: FileEntry, now: Instant = Clock.System.now()): FileEntry = copy(
        size = remote.size,
        sha256 = remote.sha256,
        blocks = remote.blocks,
        modifiedAt = remote.modifiedAt,
        modifiedBy = remote.modifiedBy,
        versionVector = versionVector.merge(remote.versionVector),
        deleted = remote.deleted,
        deletedAt = remote.deletedAt,
        updatedAt = now
    )

    /**
     * Mark the entry deleted. Sets [deleted] + [deletedAt] and bumps the
     * version vector for [me] so other devices accept the deletion as a
     * causal descendant of their last view.
     */
    fun markDeleted(me: DeviceId, now: Instant = Clock.System.now()): FileEntry = copy(
        deleted = true,
        deletedAt = now,
        modifiedAt = now,
        modifiedBy = me,
        versionVector = versionVector.increment(me),
        updatedAt = now
    )

    fun isAncestorOf(other: FileEntry): Boolean = versionVector.isAncestorOf(other.versionVector)

    fun conflictsWith(other: FileEntry): Boolean = versionVector.conflictsWith(other.versionVector)

    companion object {
        /** Factory: brand-new entry from a fresh local scan (no prior history). */
        fun newLocal(
            shareId: ShareId,
            path: String,
            me: DeviceId,
            size: Long,
            sha256: ContentHash?,
            blocks: List<String>,
            entryType: EntryType = EntryType.File,
            now: Instant = Clock.System.now()
        ): FileEntry = FileEntry(
            shareId = shareId,
            path = path,
            entryType = entryType,
            size = size,
            modifiedAt = now,
            modifiedBy = me,
            versionVector = VersionVector(mapOf(me to 1L)),
            sha256 = sha256,
            blocks = blocks,
            deleted = false,
            deletedAt = null,
            updatedAt = now
        )
    }
}