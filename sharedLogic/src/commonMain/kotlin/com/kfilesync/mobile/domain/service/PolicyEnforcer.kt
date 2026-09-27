package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.ShareRepository

/**
 * Specification-pattern unified authorization (design doc §6.1.2 + Phase 3 T3.3).
 *
 * Three composable specs:
 * - [TrustedDeviceSpec]    - peer device is in the paired list (not revoked).
 * - [ShareMemberSpec]     - peer is an authorized member of the share.
 * - [PermissionSpec]      - the member's permission allows the requested
 * direction (push/pull).
 *
 * Authorization succeeds iff **all three** specs pass. The composition is
 * stateless and pure; only the data lookup is `suspend`.
 *
 * The public entry points [canPush] / [canPull] are what the Sync Engine
 * (Phase 4) and TransferService (Phase 2, share-bound jobs) ask before
 * accepting bytes for a peer-share pair.
 */
expect class PolicyEnforcer(
    deviceRepository: DeviceRepository,
    shareRepository: ShareRepository
) {
    /** Can [peer] *push* bytes into our local mirror of [shareId]? */
    suspend fun canPush(peer: DeviceId, shareId: ShareId): Boolean 

    suspend fun canPull(peer: DeviceId, shareId: ShareId): Boolean 

}

