package com.kfilesync.mobile.application.handler

import com.kfilesync.mobile.application.dto.ShareAuthorizeDto
import com.kfilesync.mobile.application.dto.ShareLeaveDto
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.event.ShareAccepted
import com.kfilesync.mobile.domain.event.ShareLeft
import com.kfilesync.mobile.domain.event.TrustRevoked
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.ShareRepository
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Cross-cutting handler for cascade effects when an aggregate changes state.
 *
 * Phase 1 (T1.5) wired the registration plumbing only.
 * Phase 3 (T3.3) fills in the share-side cascade:
 *
 * - `TrustRevoked(deviceId)` -> drop every membership row for that device,
 *   and *leave* every share that we joined because of an invitation from
 *   that device (we no longer have a way to authenticate the creator).
 *
 * Phase 4 will add the sync-cascade body (orphan tombstones, etc.).
 *
 * The handler runs on its own [SupervisorJob] scope so one failing cascade
 * doesn't tear down sibling handlers. All work is best-effort + logged -
 * cascade failures don't fail the originating user action.
 */
class CascadeHandler(
    private val shareRepository: ShareRepository,
    private val deviceRepository: DeviceRepository,
    private val httpClient: LanSyncHttpClient,
    private val localIdentityProvider: LocalIdentityProvider,
    private val eventBus: EventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Instant = { Clock.System.now() }
) {

    fun register(eventBus: EventBus) {
        eventBus.subscribe(TrustRevoked::class) { event ->
            scope.launch { cascadeTrustRevocation(event) }
        }

        // Issue #28: when the local user accepts an invitation, tell the
        // creator we're now a member so they can mark us authorized on
        // their side.
        eventBus.subscribe(ShareAccepted::class) { event ->
            scope.launch { notifyCreatorOfAcceptance(event) }
        }

        // When the local user leaves a share, tell the creator via /share/leave
        // so they drop our membership row. Inbound /share/leave is handled by
        // ShareServiceImpl.onShareLeave, which deliberately does NOT re-publish
        // ShareLeft - that prevents a notify ping-pong between the two peers.
        eventBus.subscribe(ShareLeft::class) { event ->
            scope.launch { notifyCreatorOfLeave(event) }
        }

        Napier.d("CascadeHandler registered (TrustRevoked + ShareAccepted + ShareLeft)")
    }

    /**
     * Tell the share creator we accepted by POSTing '/share/authorize'.
     * Best-effort: failure is logged but the local share row is already
     * marked Active; the creator can also pick us up via the next sync.
     */
    private suspend fun notifyCreatorOfAcceptance(event: ShareAccepted) {
        runCatching {
            val share = shareRepository.findById(event.shareId) ?: return@runCatching
            val creator = deviceRepository.findById(share.createdBy) ?: return@runCatching
            val addr = creator.addresses.firstOrNull() ?: return@runCatching
            val baseUrl = "https://${addr.host}:${addr.port}"
            httpClient.postShareAuthorize(
                baseUrl = baseUrl,
                body = ShareAuthorizeDto(
                    shareId = event.shareId.value,
                    accepted = true
                )
            )
            Napier.i("posted /share/authorize for ${event.shareId.value} to ${creator.alias}")
        }.onFailure {
            Napier.w("share/authorize cascade failed for ${event.shareId.value}: ${it.message}")
        }
    }

    /**
     * Tell the share creator we left by POSTing '/share/leave'. Guarded to skip
     * three cases where the notification is impossible or meaningless:
     * - the share row is already gone (declining an invitation deletes it),
     * - we ARE the creator (no upstream to notify),
     * - the creator is no longer a Paired peer (trust-revocation cascade also
     *   publishes ShareLeft, but we can't and shouldn't reach a revoked peer).
     */
    private suspend fun notifyCreatorOfLeave(event: ShareLeft) {
        runCatching {
            val share = shareRepository.findById(event.shareId) ?: return@runCatching
            val local = localIdentityProvider.current()
            if (share.createdBy == local.deviceId) return@runCatching
            val creator = deviceRepository.findById(share.createdBy) ?: return@runCatching
            if (creator.state !is com.kfilesync.mobile.domain.model.DeviceState.Paired) return@runCatching
            val addr = creator.addresses.firstOrNull() ?: return@runCatching
            val baseUrl = "https://${addr.host}:${addr.port}"
            httpClient.postShareLeave(
                baseUrl = baseUrl,
                body = ShareLeaveDto(
                    shareId = event.shareId.value,
                    deviceId = local.deviceId.value,
                    leftAtMs = clock().toEpochMilliseconds()
                )
            )
            Napier.i("posted /share/leave for ${event.shareId.value} to ${creator.alias}")
        }.onFailure {
            Napier.w("share/leave cascade failed for ${event.shareId.value}: ${it.message}")
        }
    }

    /**
     * When trust is revoked, every share that was originally invited by the
     * now-untrusted device becomes unreachable (we can't talk to the
     * creator any more), so we mark it [ShareStatus.Left]. For shares we're
     * a member of but didn't create, we also drop the peer's membership row
     * so [com.kfilesync.mobile.domain.service.PolicyEnforcer] rejects any
     * future request from them.
     */
    private suspend fun cascadeTrustRevocation(event: TrustRevoked) {
        runCatching {
            val peerShares = shareRepository.findByMember(event.deviceId)

            // 1. Drop membership rows everywhere this device appeared.
            shareRepository.removeMembershipsForDevice(event.deviceId)

            // 2. For shares the revoked device *created*, mark Left.
            for (share in peerShares) {
                if (share.createdBy == event.deviceId &&
                    share.status != ShareStatus.Left
                ) {
                    val left = share.leave(event.revokedAt).getOrNull() ?: continue
                    shareRepository.save(left)
                    eventBus.publish(
                        ShareLeft(
                            shareId = share.id,
                            reason = "trust revoked for share creator ${event.deviceId.value.take(8)}"
                        )
                    )
                }
            }

            Napier.i("cascade: trust-revoked ${event.deviceId.value.take(12)} touched ${peerShares.size} share(s)")
        }.onFailure {
            Napier.w("CascadeHandler.cascadeTrustRevocation failed", it)
        }
    }
}