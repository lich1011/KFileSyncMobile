package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.domain.model.BlockLocation
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.port.FileIndexRepository

/** SQLDelight-backed [FileIndexRepository]. Phase 4 (T4.1) fills these in. */
class SqlDelightFileIndexRepo /* (private val db: KFileSyncDatabase) */ : FileIndexRepository {

    override suspend fun getIndex(shareId: ShareId): List<FileEntry> = TODO("Phase 4 T4.1")

    override suspend fun getIncremental(shareId: ShareId, sinceVersion: Long): List<FileEntry> = TODO("Phase 4 T4.1")

    override suspend fun upsertEntry(entry: FileEntry): Unit = TODO("Phase 4 T4.1")

    override suspend fun upsertEntriesBatch(entries: List<FileEntry>): Unit = TODO("Phase 4 T4.1")

    override suspend fun findBlocksByHash(hash: String): List<BlockLocation> = TODO("Phase 4 T4.3")
}