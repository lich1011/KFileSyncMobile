package com.kfilesync.mobile.application.dto

import com.kfilesync.mobile.domain.model.ContentHash
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.EntryType
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.VersionVector
import kotlin.time.Instant

/**
 * Wire <-> domain mapping for the file-index types (T4.5).
 *
 * Centralised here so both `GET /sync/index` server-side and the receiving
 * client agree on the encoding. The domain layer doesn't depend on the
 * wire format \u2014 these are application-layer helpers.
 */

fun FileEntry.toDto(): FileEntryDto = FileEntryDto(
    path = path,
    entryType = when (entryType) {
        EntryType.File -> "file"
        EntryType.Directory -> "directory"
    },
    size = size,
    modifiedAtEpochMs = modifiedAt?.toEpochMilliseconds(),
    modifiedBy = modifiedBy?.value,
    versionVector = versionVector.entries.mapKeys { it.key.value },
    sha256 = sha256?.sha256Hex,
    blocks = blocks,
    deleted = deleted
)

fun FileEntryDto.toDomain(shareId: ShareId, updatedAt: Instant): FileEntry = FileEntry(
    shareId = shareId,
    path = path,
    entryType = if (entryType == "directory") EntryType.Directory else EntryType.File,
    size = size,
    modifiedAt = modifiedAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
    modifiedBy = modifiedBy?.let { DeviceId(it) },
    versionVector = VersionVector(versionVector.mapKeys { DeviceId(it.key) }),
    sha256 = sha256?.let { ContentHash(it) },
    blocks = blocks,
    deleted = deleted,
    deletedAt = if (deleted) modifiedAtEpochMs?.let { Instant.fromEpochMilliseconds(it) } else null,
    updatedAt = updatedAt
)