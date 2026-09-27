package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.BlockInfo
import com.kfilesync.mobile.domain.model.ConflictResolution
import com.kfilesync.mobile.domain.model.ContentHash
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.EntryType
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.VersionVector
import kotlin.time.Instant
import uniffi.kfilesync_core.BlockInfo as UniffiBlockInfo

/**
 * Maps mobile's hand-written domain [FileEntry] to/from the UniFFI-generated
 * `kfilesync_core.FileEntry`, so production code (not just conformance tests)
 * can call [uniffi.kfilesync_core.applyResolution].
 *
 * Android-only: `uniffi.kfilesync_core` symbols don't exist on iOS, so this
 * lives next to [ConflictResolver]'s `androidMain` actual rather than in
 * commonMain.
 *
 * The two shapes differ in ways that require lossy or defaulted conversions:
 * - Domain `modifiedAt`/`modifiedBy` are nullable; UniFFI's are not. Falling
 *   back to `updatedAt`/"" is safe because a [FileEntry] reaching conflict
 *   resolution has always been written by someone.
 * - Domain `blocks` (`List<[BlockInfo]>`) and UniFFI's `blocks`
 *   (`List<[UniffiBlockInfo]>`) both carry real `index`/`size`/`hash` now;
 *   this is a straight field-for-field conversion, just with `Int`<->`UInt`
 *   widening.
 * - UniFFI's `FileEntry` has no `updatedAt` (mobile-only bookkeeping field
 *   for "when this index row was last touched locally"), so [toDomain]
 *   requires the caller to supply it.
 */
fun FileEntry.toUniffi(): uniffi.kfilesync_core.FileEntry = uniffi.kfilesync_core.FileEntry(
    shareId = shareId.value,
    path = path,
    entryType = when (entryType) {
        EntryType.File -> uniffi.kfilesync_core.EntryType.FILE
        EntryType.Directory -> uniffi.kfilesync_core.EntryType.DIRECTORY
    },
    size = size.toULong(),
    modifiedAtMs = (modifiedAt ?: updatedAt).toEpochMilliseconds(),
    modifiedBy = modifiedBy?.value ?: "",
    versionVector = versionVector.entries.mapKeys { it.key.value }.mapValues { it.value.toULong() },
    sha256 = sha256?.sha256Hex,
    blocks = blocks.map { UniffiBlockInfo(index = it.index.toUInt(), size = it.size.toUInt(), hash = it.hash) },
    deleted = deleted,
    deletedAtMs = deletedAt?.toEpochMilliseconds()
)

fun uniffi.kfilesync_core.FileEntry.toDomain(originalShareId: ShareId, updatedAt: Instant): FileEntry = FileEntry(
    shareId = originalShareId,
    path = path,
    entryType = when (entryType) {
        uniffi.kfilesync_core.EntryType.FILE -> EntryType.File
        uniffi.kfilesync_core.EntryType.DIRECTORY -> EntryType.Directory
    },
    size = size.toLong(),
    modifiedAt = Instant.fromEpochMilliseconds(modifiedAtMs),
    modifiedBy = modifiedBy.takeIf { it.isNotEmpty() }?.let(::DeviceId),
    versionVector = VersionVector(
        versionVector.entries.associate { (device, counter) -> DeviceId(device) to counter.toLong() }
    ),
    sha256 = sha256?.let(::ContentHash),
    blocks = blocks.map { BlockInfo(index = it.index.toInt(), size = it.size.toInt(), hash = it.hash) },
    deleted = deleted,
    deletedAt = deletedAtMs?.let(Instant::fromEpochMilliseconds),
    updatedAt = updatedAt
)

fun ConflictResolution.toUniffi(): uniffi.kfilesync_core.ConflictResolution = when (this) {
    ConflictResolution.KeepLocal -> uniffi.kfilesync_core.ConflictResolution.KEEP_LOCAL
    ConflictResolution.KeepRemote -> uniffi.kfilesync_core.ConflictResolution.KEEP_REMOTE
    ConflictResolution.KeepBoth -> uniffi.kfilesync_core.ConflictResolution.KEEP_BOTH
}