package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.db.KFileSyncDatabase
import com.kfilesync.mobile.db.Share_members
import com.kfilesync.mobile.db.Shares
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.Share
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareMember
import com.kfilesync.mobile.domain.model.SharePermission
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.model.SyncMode
import com.kfilesync.mobile.domain.port.ShareRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * SQLDelight-backed implementation of [ShareRepository] (T3.1 / T3.5).
 *
 * Storage strategy mirrors [SqlDelightTransferRepo]:
 * - [save] runs a transaction that upserts the header row, wipes every
 * existing member row for the share, then re-inserts members. This
 * keeps the on-disk and in-memory aggregate atomic; the alternative
 * (diff-by-deviceId) is more code for no win on a typical 1-10
 * member count.
 * - [updateStatus] / [updateLocalPath] are single-row hot-path updates
 * used by pause/resume/leave/accept so we don't pay for a transaction
 * on common state transitions.
 * - 'permission' on the 'shares' table records this device's permission for
 * the share - derived from the matching 'share_members' row on accept and
 * cached on the header so the UI can render permissions without a join.
 * - The per-member rows in 'share_members' continue to be the source of
 * truth for *other* devices' permissions.
 */
class SqlDelightShareRepo(
    private val db: KFileSyncDatabase
) : ShareRepository {

    override suspend fun findById(id: ShareId): Share? = withContext(Dispatchers.Default) {
        val row = db.shareQueries.findById(id.value).executeAsOneOrNull() ?: return@withContext null
        row.toDomain(membersFor(row.share_id))
    }

    override suspend fun findByMember(deviceId: DeviceId): List<Share> = withContext(Dispatchers.Default) {
        db.shareQueries.findByMember(deviceId.value).executeAsList()
            .map { it.toDomain(membersFor(it.share_id)) }
    }

    override suspend fun findAll(): List<Share> = withContext(Dispatchers.Default) {
        db.shareQueries.findAll().executeAsList()
            .map { it.toDomain(membersFor(it.share_id)) }
    }

    override suspend fun findByStatus(status: String): List<Share> = withContext(Dispatchers.Default) {
        db.shareQueries.findByStatus(status).executeAsList()
            .map { it.toDomain(membersFor(it.share_id)) }
    }

    override suspend fun save(share: Share): Unit = withContext(Dispatchers.Default) {
        db.transaction {
            db.shareQueries.insertShare(
                share_id = share.id.value,
                share_name = share.name,
                local_path = share.localPath,
                sync_mode = share.syncMode.toWire(),
                permission = share.permission.toWire(),
                status = statusToWire(share.status),
                created_by = share.createdBy.value,
                created_at = share.createdAt.toEpochMilliseconds(),
                updated_at = share.updatedAt.toEpochMilliseconds()
            )
            db.shareQueries.removeAllMembersForShare(share.id.value)
            for (m in share.members) {
                db.shareQueries.insertMember(
                    share_id = share.id.value,
                    device_id = m.deviceId.value,
                    permission = m.permission.toWire(),
                    authorized_by = m.authorizedBy.value,
                    authorized_at = m.authorizedAt.toEpochMilliseconds()
                )
            }
        }
    }

    override suspend fun updateStatus(shareId: ShareId, status: String): Unit = withContext(Dispatchers.Default) {
        db.shareQueries.updateStatus(
            status = status,
            updated_at = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            share_id = shareId.value
        )
    }

    override suspend fun updateLocalPath(shareId: ShareId, localPath: String, status: String): Unit =
        withContext(Dispatchers.Default) {
            db.shareQueries.updateLocalPath(
                local_path = localPath,
                status = status,
                updated_at = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                share_id = shareId.value
            )
        }

    override suspend fun addMember(shareId: ShareId, member: ShareMember): Unit = withContext(Dispatchers.Default) {
        db.shareQueries.insertMember(
            share_id = shareId.value,
            device_id = member.deviceId.value,
            permission = member.permission.toWire(),
            authorized_by = member.authorizedBy.value,
            authorized_at = member.authorizedAt.toEpochMilliseconds()
        )
    }

    override suspend fun removeMember(shareId: ShareId, deviceId: DeviceId): Unit = withContext(Dispatchers.Default) {
        db.shareQueries.removeMember(share_id = shareId.value, device_id = deviceId.value)
    }

    override suspend fun delete(shareId: ShareId): Unit = withContext(Dispatchers.Default) {
        // FK cascade nukes share_members rows automatically.
        db.shareQueries.deleteShare(shareId.value)
    }

    override suspend fun removeMembershipsForDevice(deviceId: DeviceId): Unit = withContext(Dispatchers.Default) {
        db.shareQueries.removeMembershipsForDevice(deviceId.value)
    }

    // -------- row -> domain --------

    private fun membersFor(shareIdValue: String): List<Share_members> =
        db.shareQueries.getMembersForShare(shareIdValue).executeAsList()

    private fun Shares.toDomain(memberRows: List<Share_members>): Share = Share(
        id = ShareId(share_id),
        name = share_name,
        localPath = local_path,
        syncMode = SyncMode.fromWire(sync_mode),
        permission = SharePermission.fromWire(permission),
        status = statusFromWire(status),
        createdBy = DeviceId(created_by),
        createdAt = Instant.fromEpochMilliseconds(created_at),
        updatedAt = Instant.fromEpochMilliseconds(updated_at),
        members = memberRows.map {
            ShareMember(
                deviceId = DeviceId(it.device_id),
                permission = SharePermission.fromWire(it.permission),
                authorizedBy = DeviceId(it.authorized_by),
                authorizedAt = Instant.fromEpochMilliseconds(it.authorized_at)
            )
        }
    )
}

internal fun statusToWire(s: ShareStatus): String = when (s) {
    ShareStatus.Pending -> "pending"
    ShareStatus.Active -> "active"
    ShareStatus.Paused -> "paused"
    ShareStatus.Left -> "left"
}

internal fun statusFromWire(v: String): ShareStatus = when (v) {
    "pending" -> ShareStatus.Pending
    "active" -> ShareStatus.Active
    "paused" -> ShareStatus.Paused
    "left" -> ShareStatus.Left
    else -> ShareStatus.Pending
}