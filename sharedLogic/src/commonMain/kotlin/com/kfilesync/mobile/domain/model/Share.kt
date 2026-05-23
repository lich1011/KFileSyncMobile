package com.kfilesync.mobile.domain.model

import kotlin.time.Instant

@kotlin.jvm.JvmInline
value class ShareId(val value: String)

/** Permission value object. */
enum class SharePermission {
    ReadOnly, ReadWrite, SendOnly, ReceiveOnly;

    fun canPush(): Boolean = this == ReadWrite || this == SendOnly
    fun canPull(): Boolean = this == ReadWrite || this == ReadOnly || this == ReceiveOnly
}

enum class SyncMode { OneWayPush, OneWayPull, TwoWay }

enum class ShareStatus { Active, Paused }

data class ShareMember(
    val deviceId: DeviceId,
    val permission: SharePermission,
    val authorizedBy: DeviceId,
    val authorizedAt: Instant
)

/** Share aggregate root. Phase 3 (T3.1) expands with policy methods. */
data class Share(
    val id: ShareId,
    val name: String,
    val localPath: String,
    val syncMode: SyncMode,
    val status: ShareStatus,
    val createdBy: DeviceId,
    val createdAt: Instant,
    val members: List<ShareMember> = emptyList()
)