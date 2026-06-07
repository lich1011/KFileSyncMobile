package com.kfilesync.mobile.domain.model

/**
 * User decision when resolving a [SyncConflict].
 *
 * Defined in the domain model layer so that both the domain service
 * ([com.kfilesync.mobile.domain.service.ConflictResolver]) and the
 * application service ([com.kfilesync.mobile.application.service.SyncAppService])
 * can reference it without creating a reverse dependency.
 *
 * Phase 4 (T4.4) wires these into the deterministic resolver pipeline.
 */
sealed class ConflictResolution {
    data object KeepLocal : ConflictResolution()
    data object KeepRemote : ConflictResolution()
    data object KeepBoth : ConflictResolution()
}
