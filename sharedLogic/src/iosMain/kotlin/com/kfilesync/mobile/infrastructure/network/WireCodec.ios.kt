package com.kfilesync.mobile.infrastructure.network

import com.kfilesync.mobile.application.dto.DeviceInfoDto
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
import com.kfilesync.mobile.application.dto.TransferRequestDto
import kotlinx.serialization.json.Json

/**
 * iOS actual for [WireCodec] backed by kotlinx.serialization using the exact
 * snake_case `@SerialName` annotations in `ApiModels.kt` (matching KFileSyncCore).
 */
actual object WireCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    actual fun encodeDeviceInfo(dto: DeviceInfoDto): ByteArray =
        json.encodeToString(DeviceInfoDto.serializer(), dto).encodeToByteArray()

    actual fun decodeDeviceInfo(bytes: ByteArray): DeviceInfoDto =
        json.decodeFromString(DeviceInfoDto.serializer(), bytes.decodeToString())

    actual fun encodePairRequest(dto: PairRequestDto): ByteArray =
        json.encodeToString(PairRequestDto.serializer(), dto).encodeToByteArray()

    actual fun decodePairRequest(bytes: ByteArray): PairRequestDto =
        json.decodeFromString(PairRequestDto.serializer(), bytes.decodeToString())

    actual fun encodePairConfirm(dto: PairConfirmDto): ByteArray =
        json.encodeToString(PairConfirmDto.serializer(), dto).encodeToByteArray()

    actual fun decodePairConfirm(bytes: ByteArray): PairConfirmDto =
        json.decodeFromString(PairConfirmDto.serializer(), bytes.decodeToString())

    actual fun encodePairRevoke(dto: PairRevokeDto): ByteArray =
        json.encodeToString(PairRevokeDto.serializer(), dto).encodeToByteArray()

    actual fun decodePairRevoke(bytes: ByteArray): PairRevokeDto =
        json.decodeFromString(PairRevokeDto.serializer(), bytes.decodeToString())

    actual fun encodePairResult(dto: PairResultDto): ByteArray =
        json.encodeToString(PairResultDto.serializer(), dto).encodeToByteArray()

    actual fun decodePairResult(bytes: ByteArray): PairResultDto =
        json.decodeFromString(PairResultDto.serializer(), bytes.decodeToString())

    actual fun encodeShareInvite(dto: ShareInviteDto): ByteArray =
        json.encodeToString(ShareInviteDto.serializer(), dto).encodeToByteArray()

    actual fun decodeShareInvite(bytes: ByteArray): ShareInviteDto =
        json.decodeFromString(ShareInviteDto.serializer(), bytes.decodeToString())

    actual fun encodeShareAuthorize(dto: ShareAuthorizeDto): ByteArray =
        json.encodeToString(ShareAuthorizeDto.serializer(), dto).encodeToByteArray()

    actual fun decodeShareAuthorize(bytes: ByteArray): ShareAuthorizeDto =
        json.decodeFromString(ShareAuthorizeDto.serializer(), bytes.decodeToString())

    actual fun encodeShareLeave(dto: ShareLeaveDto): ByteArray =
        json.encodeToString(ShareLeaveDto.serializer(), dto).encodeToByteArray()

    actual fun decodeShareLeave(bytes: ByteArray): ShareLeaveDto =
        json.decodeFromString(ShareLeaveDto.serializer(), bytes.decodeToString())

    actual fun encodeIndexResponse(dto: IndexResponseDto): ByteArray =
        json.encodeToString(IndexResponseDto.serializer(), dto).encodeToByteArray()

    actual fun decodeIndexResponse(bytes: ByteArray): IndexResponseDto =
        json.decodeFromString(IndexResponseDto.serializer(), bytes.decodeToString())

    actual fun encodeTransferRequest(dto: TransferRequestDto): ByteArray =
        json.encodeToString(TransferRequestDto.serializer(), dto).encodeToByteArray()

    actual fun decodeTransferRequest(bytes: ByteArray): TransferRequestDto =
        json.decodeFromString(TransferRequestDto.serializer(), bytes.decodeToString())

    actual fun encodeTransferAccept(dto: TransferAcceptDto): ByteArray =
        json.encodeToString(TransferAcceptDto.serializer(), dto).encodeToByteArray()

    actual fun decodeTransferAccept(bytes: ByteArray): TransferAcceptDto =
        json.decodeFromString(TransferAcceptDto.serializer(), bytes.decodeToString())

    actual fun encodeTransferChunkAck(dto: TransferChunkAckDto): ByteArray =
        json.encodeToString(TransferChunkAckDto.serializer(), dto).encodeToByteArray()

    actual fun decodeTransferChunkAck(bytes: ByteArray): TransferChunkAckDto =
        json.decodeFromString(TransferChunkAckDto.serializer(), bytes.decodeToString())

    actual fun encodeTransferCancel(dto: TransferCancelDto): ByteArray =
        json.encodeToString(TransferCancelDto.serializer(), dto).encodeToByteArray()

    actual fun decodeTransferCancel(bytes: ByteArray): TransferCancelDto =
        json.decodeFromString(TransferCancelDto.serializer(), bytes.decodeToString())
}
