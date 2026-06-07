package com.kfilesync.mobile.domain.model

import com.kfilesync.mobile.domain.DomainError
import kotlin.time.Clock
import kotlin.time.Instant

@kotlin.jvm.JvmInline
value class ShareId(val value: String)

/**
 * Permission value object (design doc §6.1.2).
 *
 * Encodes who can *write into* this share's local mirror (push) and who can
 * *receive updates from it* (pull). Mapping to direction is intentionally
 * asymmetric so a "Send only" device can populate a share without seeing
 * incoming changes, and vice versa for "Receive only".
 */
enum class SharePermission {
    ReadOnly, ReadWrite, SendOnly, ReceiveOnly;

    fun canPush(): Boolean = this == ReadWrite || this == SendOnly

    fun canPull(): Boolean = this == ReadWrite || this == ReadOnly || this == ReceiveOnly

    /** Wire string used by `/share/invite` and `/share/authorize` (snake_case). */
    fun toWire(): String = when (this) {
        ReadOnly -> "read_only"
        ReadWrite -> "read_write"
        SendOnly -> "send_only"
        ReceiveOnly -> "receive_only"
    }

    companion object {
        fun fromWire(value: String): SharePermission = when (value) {
            "read_only" -> ReadOnly
            "read_write" -> ReadWrite
            "send_only" -> SendOnly
            "receive_only" -> ReceiveOnly
            else -> ReadOnly // safe default: minimum privilege
        }
    }
}

enum class SyncMode {
    OneWayPush, OneWayPull, TwoWay;

    fun toWire(): String = when (this) {
        OneWayPush -> "one_way_push"
        OneWayPull -> "one_way_pull"
        TwoWay -> "two_way"
    }

    companion object {
        fun fromWire(value: String): SyncMode = when (value) {
            "one_way_push" -> OneWayPush
            "one_way_pull" -> OneWayPull
            "two_way" -> TwoWay
            else -> TwoWay
        }
    }
}

/**
 * Share lifecycle status (design doc §11.4).
 *
 * - `Pending`: invitation received, awaiting user accept. Persisted so it survives a restart.
 * - `Active`: user accepted, sync engine is allowed to push/pull this share.
 * - `Paused`: user (or policy engine) hit pause; chunks must not flow.
 * - `Left`: user left the share (or trust to creator revoked).
 * - Soft-tombstone; row stays so the UI can show a "you left ..." entry until cleared.
 */
enum class ShareStatus { Pending, Active, Paused, Left }

data class ShareMember(
    val deviceId: DeviceId,
    val permission: SharePermission,
    val authorizedBy: DeviceId,
    val authorizedAt: Instant
)

/**
 * Share aggregate root (design doc §6.1.2 + §11.4 + Phase 3 T3.1).
 *
 * Invariants:
 * - `members` contains at least the creator unless the share was just invited
 * and not yet accepted (in which case `localPath` is empty too).
 * - Once `Active`, the share has a non-empty `localPath`.
 * - `Left` is terminal - no state method moves out of it.
 *
 * State transitions are pure (return `Result<Share>`); all I/O sits in the
 * application service.
 */
data class Share(
    val id: ShareId,
    val name: String,
    val localPath: String,
    val syncMode: SyncMode,
    val permission: SharePermission,
    val status: ShareStatus,
    val createdBy: DeviceId,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val members: List<ShareMember> = emptyList()
) {
    /** True if the user has accepted and the share is currently allowed to sync. */
    val isActive: Boolean get() = status == ShareStatus.Active

    /** True if this device's permission allows sending bytes to the peer. */
    fun canPush(): Boolean = permission.canPush()

    /** True if this device's permission allows receiving bytes from the peer. */
    fun canPull(): Boolean = permission.canPull()

    /**
     * Accept a pending invitation. Caller must have validated the [localPath]
     * is writable; the aggregate just records it.
     */
    fun accept(localPath: String, now: Instant = Clock.System.now()): Result<Share> = when (status) {
        ShareStatus.Pending -> {
            if (localPath.isBlank()) {
                Result.failure(DomainError.InvalidStateTransition("localPath required to accept"))
            } else {
                Result.success(copy(localPath = localPath, status = ShareStatus.Active, updatedAt = now))
            }
        }
        else -> Result.failure(DomainError.InvalidStateTransition("can only accept from Pending, was $status"))
    }

    fun pause(now: Instant = Clock.System.now()): Result<Share> = when (status) {
        ShareStatus.Active -> Result.success(copy(status = ShareStatus.Paused, updatedAt = now))
        else -> Result.failure(DomainError.InvalidStateTransition("can only pause from Active, was $status"))
    }

    fun resume(now: Instant = Clock.System.now()): Result<Share> = when (status) {
        ShareStatus.Paused -> Result.success(copy(status = ShareStatus.Active, updatedAt = now))
        else -> Result.failure(DomainError.InvalidStateTransition("can only resume from Paused, was $status"))
    }

    /** User leaves the share. Terminal. */
    fun leave(now: Instant = Clock.System.now()): Result<Share> = when (status) {
        ShareStatus.Left -> Result.failure(DomainError.InvalidStateTransition("already Left"))
        else -> Result.success(copy(status = ShareStatus.Left, updatedAt = now))
    }

    /** Add or replace a member by deviceId. Pure - caller persists via ShareRepository. */
    fun withMember(member: ShareMember): Share {
        val updated = members.filterNot { it.deviceId == member.deviceId } + member
        return copy(members = updated)
    }

    /** Remove a member by deviceId. Returns the share unchanged if no such member. */
    fun withoutMember(deviceId: DeviceId): Share =
        copy(members = members.filterNot { it.deviceId == deviceId })

    /** Lookup a member by deviceId. */
    fun memberOf(deviceId: DeviceId): ShareMember? = members.firstOrNull { it.deviceId == deviceId }

    companion object {
        /**
         * Factory: build a `Pending` share from a received invitation. The local
         * path is left blank until the user picks a destination directory in
         * the accept dialog.
         */
        fun newInvitation(
            id: ShareId,
            name: String,
            createdBy: DeviceId,
            permission: SharePermission,
            syncMode: SyncMode,
            now: Instant = Clock.System.now()
        ): Share = Share(
            id = id,
            name = name,
            localPath = "",
            syncMode = syncMode,
            permission = permission,
            status = ShareStatus.Pending,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
            // The creator is implicitly a member with ReadWrite. The local
            // device joins as a member only after `accept()`; we don't model
            // it here because the wire `share/invite` doesn't tell us our
            // own member row yet - it arrives via `share/authorize`.
            members = listOf(
                ShareMember(
                    deviceId = createdBy,
                    permission = SharePermission.ReadWrite,
                    authorizedBy = createdBy,
                    authorizedAt = now
                )
            )
        )
    }
}