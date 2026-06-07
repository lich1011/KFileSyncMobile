package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.db.File_entries
import com.kfilesync.mobile.db.KFileSyncDatabase
import com.kfilesync.mobile.domain.model.BlockLocation
import com.kfilesync.mobile.domain.model.ContentHash
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.EntryType
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.VersionVector
import com.kfilesync.mobile.domain.port.FileIndexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * SQLDelight-backed implementation of [FileIndexRepository] (T4.1 / T4.8).
 *
 * Storage notes:
 * - `version_vector` is JSON-encoded `Map<String, Long>` (deviceId hex -> counter).
 * We accept the JSON-encode/decode cost per row because version-vector
 * reads are dominated by full-index scans (1x) rather than per-byte hot
 * paths. A future schema migration could move the vector to a side table.
 * - `blocks` is JSON-encoded `List<String>` (per-chunk BLAKE3 hex). Same
 * trade-off as the chunk_hashes column on transfer_items.
 * - `entry_type` / `deleted` use the same string / 0/1 conventions as the
 * desktop client so a future "sync the SQLite file directly" optimisation
 * remains an option.
 *
 * Batch inserts via [upsertEntriesBatch] run in a single transaction –
 * indexer scans tend to touch hundreds of rows on the first scan of a new
 * share, and per-row autocommit is ~5x slower than batched.
 */
class SqlDelightFileIndexRepo(
    private val db: KFileSyncDatabase,
    private val json: Json = DEFAULT_JSON
) : FileIndexRepository {

    override suspend fun getIndex(shareId: ShareId): List<FileEntry> = withContext(Dispatchers.Default) {
        db.fileEntryQueries.getIndex(shareId.value).executeAsList().map { it.toDomain() }
    }

    override suspend fun getIncremental(shareId: ShareId, sinceEpochMs: Long): List<FileEntry> =
        withContext(Dispatchers.Default) {
            db.fileEntryQueries.getIncremental(shareId.value, sinceEpochMs).executeAsList().map { it.toDomain() }
        }

    override suspend fun findByPath(shareId: ShareId, path: String): FileEntry? =
        withContext(Dispatchers.Default) {
            db.fileEntryQueries.findByPath(shareId.value, path).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun getTombstones(shareId: ShareId): List<FileEntry> = withContext(Dispatchers.Default) {
        db.fileEntryQueries.getTombstones(shareId.value).executeAsList().map { it.toDomain() }
    }

    override suspend fun upsertEntry(entry: FileEntry) = withContext(Dispatchers.Default) {
        writeRow(entry)
    }

    override suspend fun upsertEntriesBatch(entries: List<FileEntry>) = withContext(Dispatchers.Default) {
        db.transaction {
            for (entry in entries) writeRow(entry)
        }
    }

    override suspend fun cleanupTombstones(olderThanEpochMs: Long): Long = withContext(Dispatchers.Default) {
        db.fileEntryQueries.cleanupTombstones(olderThanEpochMs).value
    }

    override suspend fun findBlocksByHash(hash: String): List<BlockLocation> = withContext(Dispatchers.Default) {
        // Note: schema's findByHash matches on the *whole-file* SHA-256, which
        // returns one row per file. We synthesise a [BlockLocation] per row
        // pointing at offset 0 + the file size (best-effort: we don't store
        // per-block offsets separately yet; Phase 5 may add a block_index table
        // for true content-defined chunk dedup).
        db.fileEntryQueries.findByHash(hash).executeAsList().mapNotNull { row ->
            val blocks = decodeBlocks(row.blocks ?: "[]")
            if (blocks.isEmpty()) null else BlockLocation(
                shareId = ShareId(row.share_id),
                path = row.path,
                offset = 0L,
                size = 0 // size populated by caller when it joins against the actual entry
            )
        }
    }

    // -------- row helpers --------

    private fun writeRow(entry: FileEntry) {
        db.fileEntryQueries.upsertEntry(
            share_id = entry.shareId.value,
            path = entry.path,
            entry_type = entryTypeToWire(entry.entryType),
            size = entry.size,
            modified_at = entry.modifiedAt?.toEpochMilliseconds(),
            modified_by = entry.modifiedBy?.value,
            version_vector = encodeVector(entry.versionVector),
            sha256 = entry.sha256?.sha256Hex,
            blocks = encodeBlocks(entry.blocks),
            deleted = if (entry.deleted) 1L else 0L,
            deleted_at = entry.deletedAt?.toEpochMilliseconds(),
            updated_at = entry.updatedAt.toEpochMilliseconds()
        )
    }

    private fun File_entries.toDomain(): FileEntry = FileEntry(
        shareId = ShareId(share_id),
        path = path,
        entryType = entryTypeFromWire(entry_type),
        size = size,
        modifiedAt = modified_at?.let { Instant.fromEpochMilliseconds(it) },
        modifiedBy = modified_by?.let { DeviceId(it) },
        versionVector = decodeVector(version_vector),
        sha256 = sha256?.let { ContentHash(it) },
        blocks = decodeBlocks(blocks ?: "[]"),
        deleted = deleted != 0L,
        deletedAt = deleted_at?.let { Instant.fromEpochMilliseconds(it) },
        updatedAt = Instant.fromEpochMilliseconds(updated_at)
    )

    private fun encodeVector(v: VersionVector): String {
        val raw = v.entries.mapKeys { it.key.value }
        return json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), raw)
    }

    private fun decodeVector(raw: String): VersionVector = runCatching {
        val map = json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
        VersionVector(map.mapKeys { DeviceId(it.key) })
    }.getOrElse { VersionVector() }

    private fun encodeBlocks(blocks: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), blocks)

    private fun decodeBlocks(raw: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrElse { emptyList() }

    companion object {
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private fun entryTypeToWire(t: EntryType): String = when (t) {
    EntryType.File -> "file"
    EntryType.Directory -> "directory"
}

private fun entryTypeFromWire(v: String): EntryType = when (v) {
    "directory" -> EntryType.Directory
    else -> EntryType.File
}