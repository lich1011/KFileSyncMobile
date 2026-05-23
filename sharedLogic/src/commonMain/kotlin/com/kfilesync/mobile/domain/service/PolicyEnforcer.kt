package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.ShareRepository

/**
 * Specification-pattern unified authorization
 * device trust -> share membership -> permission check
 *
 * Phase 3 (T3.3) wires in TrustedDeviceSpec / ShareMemberSpec / PermissionSpec.
 */
class PolicyEnforcer(
    private val deviceRepository: DeviceRepository,
    private val shareRepository: ShareRepository
) {
    suspend fun canPush(peer: DeviceId, shareId: ShareId): Boolean {
        TODO("Phase 3 – compose trust + membership + permission specs.")
    }

    suspend fun canPull(peer: DeviceId, shareId: ShareId): Boolean {
        TODO("Phase 3 – compose trust + membership + permission specs.")
    }
}