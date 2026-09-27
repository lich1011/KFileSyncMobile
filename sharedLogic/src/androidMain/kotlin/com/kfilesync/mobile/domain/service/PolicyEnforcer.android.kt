package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.ShareRepository

actual class PolicyEnforcer actual constructor(
    private val deviceRepository: DeviceRepository,
    private val shareRepository: ShareRepository
) {
    actual suspend fun canPush(peer: DeviceId, shareId: ShareId): Boolean =
        evaluate(peer, shareId, uniffi.kfilesync_core.SyncDirection.PUSH) == uniffi.kfilesync_core.PolicyDecision.ALLOWED

    actual suspend fun canPull(peer: DeviceId, shareId: ShareId): Boolean =
        evaluate(peer, shareId, uniffi.kfilesync_core.SyncDirection.PULL) == uniffi.kfilesync_core.PolicyDecision.ALLOWED

    private suspend fun evaluate(
        peer: DeviceId,
        shareId: ShareId,
        direction: uniffi.kfilesync_core.SyncDirection
    ): uniffi.kfilesync_core.PolicyDecision {
        val device = deviceRepository.findById(peer) ?: return uniffi.kfilesync_core.PolicyDecision.DEVICE_NOT_PAIRED
        val share = shareRepository.findById(shareId)
        val member = share?.memberOf(peer)
        return uniffi.kfilesync_core.evaluatePolicy(
            peer = device.toUniffi(),
            share = share?.toUniffi(),
            memberPermission = member?.permission?.toUniffi(),
            direction = direction
        )
    }
}