package com.kfilesync.mobile.domain.model

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

/** File index aggregate root. */
data class FileEntry(
    val shareId: ShareId,
    val path: String,
    val entryType: EntryType = EntryType.File,
    val size: Long = 0L,
    val modifiedAt: Instant? = null,
    val modifiedBy: DeviceId? = null,
    val versionVector: VersionVector = VersionVector(),
    val sha256: ContentHash? = null,
    val blocks: List<String> = emptyList(), // BLAKE3 hex list
    val deleted: Boolean = false,
    val deletedAt: Instant? = null,
    val updatedAt: Instant
)