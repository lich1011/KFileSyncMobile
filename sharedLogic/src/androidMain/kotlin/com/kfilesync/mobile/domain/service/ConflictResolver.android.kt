package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.ConflictResolution
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.SyncConflict
import kotlin.time.Instant
import uniffi.kfilesync_core.applyResolution
import uniffi.kfilesync_core.conflictCopyName as uniffiConflictCopyName

/** Production Android implementation: delegates to kfilesync-core's UniFFI binding. */
actual class ConflictResolver actual constructor() {

    actual fun resolve(
        conflict: SyncConflict,
        resolution: ConflictResolution,
        me: DeviceId,
        now: Instant
    ): Resolution {
        val outcome = applyResolution(
            local = conflict.local.toUniffi(),
            remote = conflict.remote.toUniffi(),
            resolution = resolution.toUniffi(),
            me = me.value,
            nowMs = now.toEpochMilliseconds()
        )
        return Resolution(
            primary = outcome.primary.toDomain(conflict.shareId, now),
            conflictCopy = outcome.conflictCopy?.toDomain(conflict.shareId, now)
        )
    }

    actual fun conflictCopyName(path: String, losingDeviceId: DeviceId, conflictAt: Instant): String =
        uniffiConflictCopyName(path, losingDeviceId.value, conflictAt.toEpochMilliseconds())
}