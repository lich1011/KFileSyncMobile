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
    shareId = shareId.value,
    path = path,
    entryType = when (entryType) {
        EntryType.File -> "file"
        EntryType.Directory -> "directory"
    },
    size = size,
    modifiedAtEpochMs = (modifiedAt ?: updatedAt).toEpochMilliseconds(),
    modifiedBy = modifiedBy?.value.orEmpty(),
    versionVector = versionVector.entries.mapKeys { it.key.value },
    sha256 = sha256?.sha256Hex,
    // FileEntry.blocks is a bare BLAKE3-hex list; block size isn't tracked
    // domain-side (dead field in production - see FileEntry.blocks), so we
    // report 0 rather than inventing a chunking strategy here.
    blocks = blocks.mapIndexed { index, hash -> BlockInfoDto(index = index, size = 0, hash = hash) },
    deleted = deleted,
    deletedAtMs = deletedAt?.toEpochMilliseconds()
)

fun FileEntryDto.toDomain(shareId: ShareId, updatedAt: Instant): FileEntry = FileEntry(
    shareId = shareId,
    path = path,
    entryType = if (entryType == "directory") EntryType.Directory else EntryType.File,
    size = size,
    modifiedAt = Instant.fromEpochMilliseconds(modifiedAtEpochMs),
    modifiedBy = modifiedBy.takeIf { it.isNotBlank() }?.let { DeviceId(it) },
    versionVector = VersionVector(versionVector.mapKeys { DeviceId(it.key) }),
    sha256 = sha256?.let { ContentHash(it) },
    blocks = blocks.map { it.hash },
    deleted = deleted,
    deletedAt = deletedAtMs?.let { Instant.fromEpochMilliseconds(it) },
    updatedAt = updatedAt
)