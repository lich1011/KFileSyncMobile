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

/**
 * Delegates to core's 13 non-generic `parse_*`/`encode_*` UniFFI exports
 * (ADR-018), converting through `toCore()`/`toMobile()` first - see
 * `CoreDtoMapping.android.kt`.
 *
 * `parse_*`/`encode_*` throw `ParseException`/`EncodeException` on malformed
 * input; those propagate uncaught to the caller (Ktor's existing
 * `StatusPages` catch-all on the server, or the existing `catch (t:
 * Throwable)` per-call wrappers on the client).
 */
actual object WireCodec {
    actual fun encodeDeviceInfo(dto: DeviceInfoDto): ByteArray =
        uniffi.kfilesync_core.encodeDeviceInfo(dto.toCore())
    actual fun decodeDeviceInfo(bytes: ByteArray): DeviceInfoDto =
        uniffi.kfilesync_core.parseDeviceInfo(bytes).toMobile()

    actual fun encodePairRequest(dto: PairRequestDto): ByteArray =
        uniffi.kfilesync_core.encodePairRequest(dto.toCore())
    actual fun decodePairRequest(bytes: ByteArray): PairRequestDto =
        uniffi.kfilesync_core.parsePairRequest(bytes).toMobile()

    actual fun encodePairConfirm(dto: PairConfirmDto): ByteArray =
        uniffi.kfilesync_core.encodePairConfirm(dto.toCore())
    actual fun decodePairConfirm(bytes: ByteArray): PairConfirmDto =
        uniffi.kfilesync_core.parsePairConfirm(bytes).toMobile()

    actual fun encodePairRevoke(dto: PairRevokeDto): ByteArray =
        uniffi.kfilesync_core.encodePairRevoke(dto.toCore())
    actual fun decodePairRevoke(bytes: ByteArray): PairRevokeDto =
        uniffi.kfilesync_core.parsePairRevoke(bytes).toMobile()

    actual fun encodePairResult(dto: PairResultDto): ByteArray =
        uniffi.kfilesync_core.encodePairResult(dto.toCore())
    actual fun decodePairResult(bytes: ByteArray): PairResultDto =
        uniffi.kfilesync_core.parsePairResult(bytes).toMobile()

    actual fun encodeShareInvite(dto: ShareInviteDto): ByteArray =
        uniffi.kfilesync_core.encodeShareInvite(dto.toCore())
    actual fun decodeShareInvite(bytes: ByteArray): ShareInviteDto =
        uniffi.kfilesync_core.parseShareInvite(bytes).toMobile()

    actual fun encodeShareAuthorize(dto: ShareAuthorizeDto): ByteArray =
        uniffi.kfilesync_core.encodeShareAuthorize(dto.toCore())
    actual fun decodeShareAuthorize(bytes: ByteArray): ShareAuthorizeDto =
        uniffi.kfilesync_core.parseShareAuthorize(bytes).toMobile()

    actual fun encodeShareLeave(dto: ShareLeaveDto): ByteArray =
        uniffi.kfilesync_core.encodeShareLeave(dto.toCore())
    actual fun decodeShareLeave(bytes: ByteArray): ShareLeaveDto =
        uniffi.kfilesync_core.parseShareLeave(bytes).toMobile()

    actual fun encodeIndexResponse(dto: IndexResponseDto): ByteArray =
        uniffi.kfilesync_core.encodeIndexResponse(dto.toCore())
    actual fun decodeIndexResponse(bytes: ByteArray): IndexResponseDto =
        uniffi.kfilesync_core.parseIndexResponse(bytes).toMobile()

    actual fun encodeTransferRequest(dto: TransferRequestDto): ByteArray =
        uniffi.kfilesync_core.encodeTransferRequest(dto.toCore())
    actual fun decodeTransferRequest(bytes: ByteArray): TransferRequestDto =
        uniffi.kfilesync_core.parseTransferRequest(bytes).toMobile()

    actual fun encodeTransferAccept(dto: TransferAcceptDto): ByteArray =
        uniffi.kfilesync_core.encodeTransferAccept(dto.toCore())
    actual fun decodeTransferAccept(bytes: ByteArray): TransferAcceptDto =
        uniffi.kfilesync_core.parseTransferAccept(bytes).toMobile()

    actual fun encodeTransferChunkAck(dto: TransferChunkAckDto): ByteArray =
        uniffi.kfilesync_core.encodeTransferChunkAck(dto.toCore())
    actual fun decodeTransferChunkAck(bytes: ByteArray): TransferChunkAckDto =
        uniffi.kfilesync_core.parseTransferChunkAck(bytes).toMobile()

    actual fun encodeTransferCancel(dto: TransferCancelDto): ByteArray =
        uniffi.kfilesync_core.encodeTransferCancel(dto.toCore())
    actual fun decodeTransferCancel(bytes: ByteArray): TransferCancelDto =
        uniffi.kfilesync_core.parseTransferCancel(bytes).toMobile()
}