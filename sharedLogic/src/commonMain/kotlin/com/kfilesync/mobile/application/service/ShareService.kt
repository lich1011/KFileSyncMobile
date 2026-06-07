package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.application.dto.ShareAuthorizeDto
import com.kfilesync.mobile.application.dto.ShareInviteDto
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.DomainError
import com.kfilesync.mobile.domain.event.ShareAccepted
import com.kfilesync.mobile.domain.event.ShareInvited
import com.kfilesync.mobile.domain.event.ShareLeft
import com.kfilesync.mobile.domain.event.SharePaused
import com.kfilesync.mobile.domain.event.ShareResumed
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.Share
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareMember
import com.kfilesync.mobile.domain.model.SharePermission
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.model.SyncMode
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.ShareRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Snapshot of one share for the UI (Phase 3 T3.6).
 *
 * The UI doesn't need the chunk-hashes / full member list - keep this row
 * compact. The Shares screen renders sections by status: Pending invitations,
 * Active, Paused, Left.
 */
data class ShareRow(
    val shareId: ShareId,
    val name: String,
    val localPath: String,
    val status: ShareStatus,
    val syncMode: SyncMode,
    val permission: SharePermission,
    val createdBy: DeviceId,
    val createdByAlias: String,
    val memberCount: Int,
    val updatedAt: Instant
)

/**
 * Driving port: shared-folder use cases (design doc §6.2.1 + Phase 3 T3.1-T3.4).
 */
interface ShareAppService {

    /** Sets up a 'Pending' share row from an inbound '/share/invite'. Idempotent on 'shareId'. */
    suspend fun onShareInvite(body: ShareInviteDto): Result<Unit>

    /** Records an authorization update from '/share/authorize'. */
    suspend fun onShareAuthorize(body: ShareAuthorizeDto): Result<Unit>

    /** UI action: user accepted an invitation; pick a local destination directory. */
    suspend fun acceptInvitation(shareId: ShareId, localPath: String): Result<Unit>

    /** UI action: user declined an invitation; the share row is removed. */
    suspend fun declineInvitation(shareId: ShareId): Result<Unit>

    /** UI action: pause syncing for an Active share. */
    suspend fun pauseShare(shareId: ShareId): Result<Unit>

    /** UI action: resume a Paused share. */
    suspend fun resumeShare(shareId: ShareId): Result<Unit>

    /** UI action: leave the share entirely. Phase 4 will additionally inform the creator via '/share/leave'. */
    suspend fun leaveShare(shareId: ShareId): Result<Unit>

    /** Hot stream of every share row, most-recent first. */
    fun observeShares(): Flow<List<ShareRow>>

    /** Re-emit the current rows after process restart. */
    suspend fun resumeAfterRestart()
}

/**
 * Phase 3 implementation (T3.1 - T3.4).
 *
 * Inbound flow (peer creates share, invites us):
 * 1. Peer POSTs '/share/invite' -> [onShareInvite] persists a 'Pending'
 * share, publishes [ShareInvited] for the UI banner.
 * 2. User opens the banner, picks a local directory -> UI calls
 * [acceptInvitation] -> flips to 'Active', persists the local mapping.
 * 3. (Phase 4) Peer POSTs '/share/authorize' once we're a member; we
 * record the authorization in [onShareAuthorize].
 *
 * Outbound flow (Phase 2 - mobile creates a share) is deferred to a future
 * milestone; mobile MVP only joins shares created by the desktop.
 *
 * Trust check: every wire callback verifies the sender is in
 * [DeviceState.Paired] before touching the database. An invite from an
 * unknown / revoked peer is rejected with `DomainError.DeviceNotTrusted`.
 */
class ShareServiceImpl(
    private val shareRepository: ShareRepository,
    private val deviceRepository: DeviceRepository,
    private val eventBus: EventBus,
    private val localIdentityProvider: LocalIdentityProvider,
    private val clock: () -> Instant = { Clock.System.now() }
) : ShareAppService {

    private val rows = MutableStateFlow<List<ShareRow>>(emptyList())
    private val lock = Mutex()

    override fun observeShares(): Flow<List<ShareRow>> = rows.asStateFlow()

    override suspend fun onShareInvite(body: ShareInviteDto): Result<Unit> = lock.withLock {
        val fromId = DeviceId(body.fromDeviceId)
        val from = deviceRepository.findById(fromId)
        if (from == null || from.state !is DeviceState.Paired) {
            Napier.w("rejecting /share/invite from untrusted ${body.fromDeviceId}")
            return Result.failure(DomainError.DeviceNotTrusted(fromId))
        }

        val shareId = ShareId(body.shareId)
        val existing = shareRepository.findById(shareId)
        if (existing != null) {
            // Idempotency: re-invitation while already Pending is a no-op;
            // re-invitation after we left re-opens as Pending.
            when (existing.status) {
                ShareStatus.Pending -> {
                    Napier.d("share ${body.shareId} already Pending; ignoring duplicate invite")
                    return Result.success(Unit)
                }
                ShareStatus.Active, ShareStatus.Paused -> {
                    Napier.d("share ${body.shareId} already Active/Paused; ignoring duplicate invite")
                    return Result.success(Unit)
                }
                ShareStatus.Left -> {
                    // Reopen as pending.
                    val reopened = existing.copy(
                        status = ShareStatus.Pending,
                        updatedAt = clock()
                    )
                    shareRepository.save(reopened)
                    publishRows()
                    eventBus.publish(toInvitedEvent(reopened, body))
                    return Result.success(Unit)
                }
            }
        }

        val share = Share.newInvitation(
            id = shareId,
            name = body.shareName,
            createdBy = fromId,
            // Issue #37: clamp the peer-asserted permission to a safe
            // default. A malicious / buggy peer could otherwise claim
            // SharePermission.Owner for itself by sending `permission =
            // "owner"`. We accept the wire string but reject anything we
            // don't know how to handle, and downgrade unexpected values
            // to ReadOnly (the most restrictive option) instead of
            // trusting it blindly.
            permission = validateInvitedPermission(body.permission),
            syncMode = SyncMode.fromWire(body.syncMode),
            now = clock()
        )

        shareRepository.save(share)
        publishRows()
        eventBus.publish(toInvitedEvent(share, body))
        Napier.i("share ${body.shareId} invited by ${from.alias}")
        Result.success(Unit)
    }

    override suspend fun onShareAuthorize(body: ShareAuthorizeDto): Result<Unit> = lock.withLock {
        val shareId = ShareId(body.shareId)
        val share = shareRepository.findById(shareId)
            ?: return Result.failure(DomainError.ShareNotFound(shareId))

        val authorizedBy = DeviceId(body.authorizedBy)
        val from = deviceRepository.findById(authorizedBy)
        if (from == null || from.state !is DeviceState.Paired) {
            return Result.failure(DomainError.DeviceNotTrusted(authorizedBy))
        }

        val member = ShareMember(
            deviceId = DeviceId(body.deviceId),
            permission = SharePermission.fromWire(body.permission),
            authorizedBy = authorizedBy,
            authorizedAt = clock()
        )

        val updated = share.withMember(member).copy(updatedAt = clock())
        shareRepository.save(updated)
        publishRows()
        Result.success(Unit)
    }

    override suspend fun acceptInvitation(shareId: ShareId, localPath: String): Result<Unit> = lock.withLock {
        val share = shareRepository.findById(shareId)
            ?: return Result.failure(DomainError.ShareNotFound(shareId))
        val accepted = share.accept(localPath, clock()).getOrElse {
            return Result.failure(it as Throwable)
        }

        // Add the local device as a member on accept - the creator already had a
        // member row from the factory. The creator will later confirm with
        // '/share/authorize', at which point we may overwrite the local row.
        val local = localIdentityProvider.current()
        val withLocal = accepted.withMember(
            ShareMember(
                deviceId = local.deviceId,
                permission = accepted.permission,
                authorizedBy = accepted.createdBy,
                authorizedAt = clock()
            )
        )

        shareRepository.save(withLocal)
        publishRows()
        eventBus.publish(
            ShareAccepted(
                shareId = shareId,
                localPath = localPath,
                syncMode = accepted.syncMode.toWire()
            )
        )
        Napier.i("share ${shareId.value} accepted; localPath=$localPath")
        Result.success(Unit)
    }

    override suspend fun declineInvitation(shareId: ShareId): Result<Unit> = lock.withLock {
        val share = shareRepository.findById(shareId)
            ?: return Result.failure(DomainError.ShareNotFound(shareId))
        if (share.status != ShareStatus.Pending) {
            return Result.failure(DomainError.InvalidStateTransition("share not Pending; was ${share.status}"))
        }

        shareRepository.delete(shareId)
        publishRows()
        eventBus.publish(ShareLeft(shareId = shareId, reason = "declined invitation"))
        Result.success(Unit)
    }

    override suspend fun pauseShare(shareId: ShareId): Result<Unit> = lock.withLock {
        val share = shareRepository.findById(shareId)
            ?: return Result.failure(DomainError.ShareNotFound(shareId))
        val paused = share.pause(clock()).getOrElse {
            return Result.failure(it as Throwable)
        }
        // save() upserts the header (status+updatedAt) + member rows in one
        // transaction; no separate updateStatus call needed.
        shareRepository.save(paused)
        publishRows()
        eventBus.publish(SharePaused(shareId))
        Result.success(Unit)
    }

    override suspend fun resumeShare(shareId: ShareId): Result<Unit> = lock.withLock {
        val share = shareRepository.findById(shareId)
            ?: return Result.failure(DomainError.ShareNotFound(shareId))
        val resumed = share.resume(clock()).getOrElse {
            return Result.failure(it as Throwable)
        }
        shareRepository.save(resumed)
        publishRows()
        eventBus.publish(ShareResumed(shareId))
        Result.success(Unit)
    }

    override suspend fun leaveShare(shareId: ShareId): Result<Unit> = lock.withLock {
        val share = shareRepository.findById(shareId)
            ?: return Result.failure(DomainError.ShareNotFound(shareId))
        val left = share.leave(clock()).getOrElse {
            return Result.failure(it as Throwable)
        }
        shareRepository.save(left)
        publishRows()
        eventBus.publish(ShareLeft(shareId, reason = "user left share"))
        Napier.i("share ${shareId.value} left by user")
        Result.success(Unit)
    }

    override suspend fun resumeAfterRestart() {
        publishRows()
    }

    //  helpers

    private suspend fun publishRows() {
        val all = shareRepository.findAll().sortedByDescending { it.updatedAt }
        val mapped = all.map { share ->
            val createdByAlias = deviceRepository.findById(share.createdBy)?.alias ?: share.createdBy.value.take(8)
            ShareRow(
                shareId = share.id,
                name = share.name,
                localPath = share.localPath,
                status = share.status,
                syncMode = share.syncMode,
                permission = share.permission,
                createdBy = share.createdBy,
                createdByAlias = createdByAlias,
                memberCount = share.members.size,
                updatedAt = share.updatedAt
            )
        }
        rows.value = mapped
    }

    private fun toInvitedEvent(share: Share, body: ShareInviteDto) = ShareInvited(
        shareId = share.id,
        shareName = share.name,
        fromDeviceId = DeviceId(body.fromDeviceId),
        permission = body.permission,
        syncMode = body.syncMode
    )

    /**
     * Translate a peer-supplied permission string into a [SharePermission],
     * rejecting anything that would grant elevated capability (issue #37).
     *
     * On the invite path the local user is the *recipient* of the share -
     * we honour Read or ReadWrite, but anything Owner-shaped is downgraded
     * because that would imply the peer is granting us control over their
     * share, which is meaningless at the protocol level.
     */
    private fun validateInvitedPermission(wire: String): SharePermission {
        val parsed = runCatching { SharePermission.fromWire(wire) }.getOrNull()
        return when (parsed) {
            SharePermission.ReadOnly, SharePermission.ReadWrite -> parsed
            else -> {
                Napier.w("unexpected permission '$wire' in /share/invite; downgrading to ReadOnly")
                SharePermission.ReadOnly
            }
        }
    }
}