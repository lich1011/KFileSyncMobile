package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.BlockInfoDto
import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.application.dto.FileEntryDto
import com.kfilesync.mobile.application.dto.IndexResponseDto
import com.kfilesync.mobile.application.dto.PairConfirmDto
import com.kfilesync.mobile.application.dto.PairRequestDto
import com.kfilesync.mobile.application.dto.PairResultDto
import com.kfilesync.mobile.application.dto.PairRevokeDto
import com.kfilesync.mobile.application.dto.ShareAuthorizeDto
import com.kfilesync.mobile.application.dto.ShareInviteDto
import com.kfilesync.mobile.application.dto.ShareLeaveDto
import com.kfilesync.mobile.application.dto.TransferAcceptDto
import com.kfilesync.mobile.application.dto.TransferCancelDto
import com.kfilesync.mobile.application.dto.TransferChunkAckDto
import com.kfilesync.mobile.application.dto.TransferFileDto
import com.kfilesync.mobile.application.dto.TransferRequestDto

/**
 * "Hand-written DTO <-> core UniFFI DTO" mapping, one layer further out than
 * `FileEntryMapping.kt`'s existing "DB row <-> hand-written DTO" mapping.
 *
 * Core's `u32`/`u64` wire fields surface in Kotlin as `UInt`/`ULong`; mobile's
 * `ApiModels.kt` uses signed `Int`/`Long` throughout (predates this bridge).
 * JSON itself doesn't distinguish signed/unsigned, so wire bytes round-trip
 * fine - these conversions only matter for in-memory representation on the
 * Kotlin side.
 */

internal fun DeviceInfoDto.toCore(): uniffi.kfilesync_core.DeviceInfoDto =
    uniffi.kfilesync_core.DeviceInfoDto(
        protocol = protocol,
        version = version,
        deviceId = deviceId,
        alias = alias,
        deviceType = deviceType,
        platform = platform,
        fingerprint = fingerprint,
        port = port.toUShort(),
        announce = announce
    )

internal fun uniffi.kfilesync_core.DeviceInfoDto.toMobile(): DeviceInfoDto =
    DeviceInfoDto(
        protocol = protocol,
        version = version,
        deviceId = deviceId,
        alias = alias,
        deviceType = deviceType,
        platform = platform,
        fingerprint = fingerprint,
        port = port.toInt(),
        announce = announce
    )

internal fun PairRequestDto.toCore(): uniffi.kfilesync_core.PairRequestDto =
    uniffi.kfilesync_core.PairRequestDto(
        requestId = requestId,
        fromDeviceId = fromDeviceId,
        fromAlias = fromAlias,
        fromPlatform = fromPlatform,
        fromFingerprint = fromFingerprint,
        nonce = nonce,
        expiresAtMs = expiresAtEpochMs
    )

internal fun uniffi.kfilesync_core.PairRequestDto.toMobile(): PairRequestDto =
    PairRequestDto(
        requestId = requestId,
        fromDeviceId = fromDeviceId,
        fromAlias = fromAlias,
        fromPlatform = fromPlatform,
        fromFingerprint = fromFingerprint,
        nonce = nonce,
        expiresAtEpochMs = expiresAtMs
    )

internal fun PairConfirmDto.toCore(): uniffi.kfilesync_core.PairConfirmDto =
    uniffi.kfilesync_core.PairConfirmDto(
        requestId = requestId,
        pin = pin,
        certificatePem = certificatePem
    )

internal fun uniffi.kfilesync_core.PairConfirmDto.toMobile(): PairConfirmDto =
    PairConfirmDto(
        requestId = requestId,
        pin = pin,
        certificatePem = certificatePem
    )

internal fun PairRevokeDto.toCore(): uniffi.kfilesync_core.PairRevokeDto =
    uniffi.kfilesync_core.PairRevokeDto(
        deviceId = deviceId,
        reason = reason,
        revokedAtMs = revokedAtMs
    )

internal fun uniffi.kfilesync_core.PairRevokeDto.toMobile(): PairRevokeDto =
    PairRevokeDto(
        deviceId = deviceId,
        reason = reason,
        revokedAtMs = revokedAtMs
    )

internal fun PairResultDto.toCore(): uniffi.kfilesync_core.PairResultDto =
    uniffi.kfilesync_core.PairResultDto(
        requestId = requestId,
        accepted = accepted,
        peerCertificatePem = peerCertificatePem,
        reason = reason
    )

internal fun uniffi.kfilesync_core.PairResultDto.toMobile(): PairResultDto =
    PairResultDto(
        requestId = requestId,
        accepted = accepted,
        peerCertificatePem = peerCertificatePem,
        reason = reason
    )

internal fun ShareInviteDto.toCore(): uniffi.kfilesync_core.ShareInviteDto =
    uniffi.kfilesync_core.ShareInviteDto(
        shareId = shareId,
        shareName = shareName,
        fromDeviceId = fromDeviceId,
        defaultPermission = permission,
        syncMode = syncMode,
        invitedAtMs = invitedAtMs
    )

internal fun uniffi.kfilesync_core.ShareInviteDto.toMobile(): ShareInviteDto =
    ShareInviteDto(
        shareId = shareId,
        shareName = shareName,
        fromDeviceId = fromDeviceId,
        permission = defaultPermission,
        syncMode = syncMode,
        invitedAtMs = invitedAtMs
    )

internal fun ShareAuthorizeDto.toCore(): uniffi.kfilesync_core.ShareAuthorizeDto =
    uniffi.kfilesync_core.ShareAuthorizeDto(
        shareId = shareId,
        accepted = accepted,
        reason = reason
    )

internal fun uniffi.kfilesync_core.ShareAuthorizeDto.toMobile(): ShareAuthorizeDto =
    ShareAuthorizeDto(
        shareId = shareId,
        accepted = accepted,
        reason = reason
    )

internal fun ShareLeaveDto.toCore(): uniffi.kfilesync_core.ShareLeaveDto =
    uniffi.kfilesync_core.ShareLeaveDto(
        shareId = shareId,
        deviceId = deviceId,
        leftAtMs = leftAtMs
    )

internal fun uniffi.kfilesync_core.ShareLeaveDto.toMobile(): ShareLeaveDto =
    ShareLeaveDto(
        shareId = shareId,
        deviceId = deviceId,
        leftAtMs = leftAtMs
    )

internal fun BlockInfoDto.toCore(): uniffi.kfilesync_core.BlockInfoDto =
    uniffi.kfilesync_core.BlockInfoDto(
        index = index.toUInt(),
        size = size.toUInt(),
        hash = hash
    )

internal fun uniffi.kfilesync_core.BlockInfoDto.toMobile(): BlockInfoDto =
    BlockInfoDto(
        index = index.toInt(),
        size = size.toInt(),
        hash = hash
    )

internal fun FileEntryDto.toCore(): uniffi.kfilesync_core.FileEntryDto =
    uniffi.kfilesync_core.FileEntryDto(
        shareId = shareId,
        path = path,
        entryType = entryType,
        size = size.toULong(),
        modifiedAtMs = modifiedAtEpochMs,
        modifiedBy = modifiedBy,
        versionVector = versionVector.mapValues { it.value.toULong() },
        sha256 = sha256,
        blocks = blocks.map { it.toCore() },
        deleted = deleted,
        deletedAtMs = deletedAtMs
    )

internal fun uniffi.kfilesync_core.FileEntryDto.toMobile(): FileEntryDto =
    FileEntryDto(
        shareId = shareId,
        path = path,
        entryType = entryType,
        size = size.toLong(),
        modifiedAtEpochMs = modifiedAtMs,
        modifiedBy = modifiedBy,
        versionVector = versionVector.mapValues { it.value.toLong() },
        sha256 = sha256,
        blocks = blocks.map { it.toMobile() },
        deleted = deleted,
        deletedAtMs = deletedAtMs
    )

internal fun IndexResponseDto.toCore(): uniffi.kfilesync_core.IndexResponseDto =
    uniffi.kfilesync_core.IndexResponseDto(
        shareId = shareId,
        indexVersion = indexVersion.toULong(),
        entries = entries.map { it.toCore() }
    )

internal fun uniffi.kfilesync_core.IndexResponseDto.toMobile(): IndexResponseDto =
    IndexResponseDto(
        shareId = shareId,
        indexVersion = indexVersion.toLong(),
        entries = entries.map { it.toMobile() }
    )

internal fun TransferFileDto.toCore(): uniffi.kfilesync_core.TransferItemDto =
    uniffi.kfilesync_core.TransferItemDto(
        fileId = fileId,
        path = path,
        size = size.toULong(),
        sha256 = sha256,
        chunkSize = chunkSize.toUInt(),
        chunkHashes = chunkHashes
    )

internal fun uniffi.kfilesync_core.TransferItemDto.toMobile(): TransferFileDto =
    TransferFileDto(
        fileId = fileId,
        path = path,
        size = size.toLong(),
        sha256 = sha256,
        chunkSize = chunkSize.toInt(),
        chunkHashes = chunkHashes
    )

internal fun TransferRequestDto.toCore(): uniffi.kfilesync_core.TransferRequestDto =
    uniffi.kfilesync_core.TransferRequestDto(
        jobId = jobId,
        sessionId = sessionId,
        fromDeviceId = fromDeviceId,
        fromAlias = fromAlias,
        shareId = shareId,
        files = files.map { it.toCore() }
    )

internal fun uniffi.kfilesync_core.TransferRequestDto.toMobile(): TransferRequestDto =
    TransferRequestDto(
        sessionId = sessionId,
        jobId = jobId,
        fromDeviceId = fromDeviceId,
        fromAlias = fromAlias,
        shareId = shareId,
        files = files.map { it.toMobile() }
    )

internal fun TransferAcceptDto.toCore(): uniffi.kfilesync_core.TransferAcceptDto =
    uniffi.kfilesync_core.TransferAcceptDto(
        sessionId = sessionId,
        jobId = jobId,
        accepted = accepted,
        reason = reason,
        skipChunks = skipChunks.mapValues { entry -> entry.value.map { it.toUInt() } }
    )

internal fun uniffi.kfilesync_core.TransferAcceptDto.toMobile(): TransferAcceptDto =
    TransferAcceptDto(
        sessionId = sessionId,
        jobId = jobId,
        accepted = accepted,
        reason = reason,
        skipChunks = skipChunks.mapValues { entry -> entry.value.map { it.toInt() } }
    )

internal fun TransferChunkAckDto.toCore(): uniffi.kfilesync_core.TransferChunkAckDto =
    uniffi.kfilesync_core.TransferChunkAckDto(
        jobId = jobId,
        fileId = fileId,
        chunkIndex = chunkIndex.toUInt(),
        verified = verified
    )

internal fun uniffi.kfilesync_core.TransferChunkAckDto.toMobile(): TransferChunkAckDto =
    TransferChunkAckDto(
        jobId = jobId,
        fileId = fileId,
        chunkIndex = chunkIndex.toInt(),
        verified = verified
    )

internal fun TransferCancelDto.toCore(): uniffi.kfilesync_core.TransferCancelDto =
    uniffi.kfilesync_core.TransferCancelDto(
        jobId = jobId,
        reason = reason
    )

internal fun uniffi.kfilesync_core.TransferCancelDto.toMobile(): TransferCancelDto =
    TransferCancelDto(
        jobId = jobId,
        reason = reason
    )