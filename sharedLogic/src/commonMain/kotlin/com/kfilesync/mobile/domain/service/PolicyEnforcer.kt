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
class PolicyEnforcer(
    private val deviceRepository: DeviceRepository,
    private val shareRepository: ShareRepository
) {
    /** Can [peer] *push* bytes into our local mirror of [shareId]? */
    suspend fun canPush(peer: DeviceId, shareId: ShareId): Boolean = evaluate(peer, shareId, Direction.Push)

    suspend fun canPull(peer: DeviceId, shareId: ShareId): Boolean = evaluate(peer, shareId, Direction.Pull)

    private suspend fun evaluate(peer: DeviceId, shareId: ShareId, direction: Direction): Boolean {
        // 1. Trust check.
        if (!TrustedDeviceSpec(deviceRepository).isSatisfiedBy(peer)) return false

        // 2. Share + membership lookup. ShareMemberSpec also tells us the peer's
        //    permission via the member row.
        val share = shareRepository.findById(shareId) ?: return false
        if (share.status != ShareStatus.Active) return false // paused/pending/left blocks all flow
        val member = share.memberOf(peer) ?: return false

        // 3. Permission direction check.
        return when (direction) {
            Direction.Push -> PermissionSpec.canPush(member.permission)
            Direction.Pull -> PermissionSpec.canPull(member.permission)
        }
    }

    private enum class Direction { Push, Pull }
}

// ----------- Individual specifications -----------

/**
 * Specification: the device is paired (not just discovered, not revoked).
 *
 * Returning true means we can open a TLS connection with it (its cert is
 * pinned in the trust manager) and that auth on the wire will succeed.
 */
class TrustedDeviceSpec(private val deviceRepository: DeviceRepository) {

    suspend fun isSatisfiedBy(deviceId: DeviceId): Boolean {
        val device = deviceRepository.findById(deviceId) ?: return false
        return device.state is DeviceState.Paired
    }
}

/**
 * Specification: the device is a current member of the share.
 *
 * Note: returns false for shares the local user has paused or left - we
 * keep the row for UI history but no traffic flows. Active/Paused split
 * is enforced in [PolicyEnforcer.evaluate] (we don't want to repeat that
 * here so we keep this spec laser-focused on membership).
 */
class ShareMemberSpec(private val shareRepository: ShareRepository) {

    suspend fun isSatisfiedBy(shareId: ShareId, deviceId: DeviceId): Boolean {
        val share = shareRepository.findById(shareId) ?: return false
        return share.memberOf(deviceId) != null
    }
}

/**
 * Specification: the peer's recorded permission allows the requested direction.
 *
 * Stateless wrapper over [com.kfilesync.mobile.domain.model.SharePermission].
 * Lives here to keep all three specs in one file.
 */
object PermissionSpec {
    fun canPush(permission: com.kfilesync.mobile.domain.model.SharePermission): Boolean = permission.canPush()

    fun canPull(permission: com.kfilesync.mobile.domain.model.SharePermission): Boolean = permission.canPull()
}