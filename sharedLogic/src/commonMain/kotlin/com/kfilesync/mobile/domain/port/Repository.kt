package com.kfilesync.mobile.domain.port

import com.kfilesync.mobile.domain.model.BlockLocation
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.JobId
import com.kfilesync.mobile.domain.model.Share
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareMember
import com.kfilesync.mobile.domain.model.TransferJob
import com.kfilesync.mobile.domain.model.TransferProgress
import com.kfilesync.mobile.domain.model.TrustStatus

// ------- Storage ports (driven side of the hexagon) -------

interface DeviceRepository {
    suspend fun findById(id: DeviceId): Device?
    suspend fun findPaired(): List<Device>
    suspend fun save(device: Device)
    suspend fun updateTrustStatus(id: DeviceId, status: TrustStatus)
}

interface ShareRepository {
    suspend fun findById(id: ShareId): Share?
    suspend fun findByMember(deviceId: DeviceId): List<Share>
    suspend fun save(share: Share)
    suspend fun addMember(shareId: ShareId, member: ShareMember)
    suspend fun removeMember(shareId: ShareId, deviceId: DeviceId)
}

interface FileIndexRepository {
    suspend fun getIndex(shareId: ShareId): List<FileEntry>
    suspend fun getIncremental(shareId: ShareId, sinceVersion: Long): List<FileEntry>
    suspend fun upsertEntry(entry: FileEntry)
    suspend fun upsertEntriesBatch(entries: List<FileEntry>)
    suspend fun findBlocksByHash(hash: String): List<BlockLocation>
}

interface TransferRepository {
    suspend fun saveJob(job: TransferJob)
    suspend fun updateProgress(jobId: JobId, progress: TransferProgress)
    suspend fun findIncompleteJobs(): List<TransferJob>
}