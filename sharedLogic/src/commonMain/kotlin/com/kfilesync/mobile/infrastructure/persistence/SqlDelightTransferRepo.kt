package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.domain.model.JobId
import com.kfilesync.mobile.domain.model.TransferJob
import com.kfilesync.mobile.domain.model.TransferProgress
import com.kfilesync.mobile.domain.port.TransferRepository

/** SQLDelight-backed [TransferRepository]. Phase 2 (T2.1) fills these in. */
class SqlDelightTransferRepo /* (private val db: KFileSyncDatabase) */ : TransferRepository {

    override suspend fun saveJob(job: TransferJob): Unit = TODO("Phase 2 T2.1")

    override suspend fun updateProgress(jobId: JobId, progress: TransferProgress): Unit = TODO("Phase 2 T2.3")

    override suspend fun findIncompleteJobs(): List<TransferJob> = TODO("Phase 2 T2.4")
}