package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.SyncConflict
import com.kfilesync.mobile.domain.model.SyncPlan
import kotlin.time.Clock
import kotlin.time.Instant

actual class SyncPlanGenerator actual constructor() {

    actual fun generate(local: List<FileEntry>, remote: List<FileEntry>): SyncPlan {
        if (local.isEmpty() && remote.isEmpty()) {
            return SyncPlan(toPull = emptyList(), toPush = emptyList(), conflicts = emptyList(), unchanged = emptyList())
        }

        val shareId: ShareId = (local.firstOrNull() ?: remote.firstOrNull())!!.shareId
        val originalByPath = local.associateBy { it.path } + remote.associateBy { it.path }

        fun uniffi.kfilesync_core.FileEntry.toDomainPreservingUpdatedAt(): FileEntry =
            toDomain(shareId, originalByPath[path]?.updatedAt ?: Clock.System.now())

        val uniffiPlan = uniffi.kfilesync_core.generate(
            localIndex = local.map { it.toUniffi() },
            remoteIndex = remote.map { it.toUniffi() }
        )

        return SyncPlan(
            toPull = uniffiPlan.toPull.map { it.toDomainPreservingUpdatedAt() },
            toPush = uniffiPlan.toPush.map { it.toDomainPreservingUpdatedAt() },
            conflicts = uniffiPlan.conflicts.map { c ->
                SyncConflict(
                    shareId = shareId,
                    path = c.path,
                    local = c.local.toDomainPreservingUpdatedAt(),
                    remote = c.remote.toDomainPreservingUpdatedAt()
                )
            },
            unchanged = uniffiPlan.unchanged.map { it.toDomainPreservingUpdatedAt() }
        )
    }
}