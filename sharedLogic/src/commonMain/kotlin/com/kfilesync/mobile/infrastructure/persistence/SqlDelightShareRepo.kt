package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.Share
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareMember
import com.kfilesync.mobile.domain.port.ShareRepository

/** SQLDelight-backed [ShareRepository]. Phase 3 (T3.1) fills these in. */
class SqlDelightShareRepo /* (private val db: KFileSyncDatabase) */ : ShareRepository {

    override suspend fun findById(id: ShareId): Share? = TODO("Phase 3 T3.1")

    override suspend fun findByMember(deviceId: DeviceId): List<Share> = TODO("Phase 3 T3.1")

    override suspend fun save(share: Share): Unit = TODO("Phase 3 T3.1")

    override suspend fun addMember(shareId: ShareId, member: ShareMember): Unit = TODO("Phase 3 T3.2")

    override suspend fun removeMember(shareId: ShareId, deviceId: DeviceId): Unit = TODO("Phase 3 T3.3")
}