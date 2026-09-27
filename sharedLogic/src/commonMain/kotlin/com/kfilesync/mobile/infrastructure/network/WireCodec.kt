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
 * Sprint 4 follow-up (ADR-018): encodes/decodes the 13 top-level wire DTOs
 * that core now exports non-generic `parse_*`/`encode_*` UniFFI functions
 * for. `ApiModels.kt`'s hand-written DTOs stay the host-side shape used by
 * `application.service` classes; the androidMain `actual` maps them to/from core's
 * UniFFI DTOs before delegating to core's codec, mirroring the "DB row <->
 * hand-written DTO" pattern already used by `FileEntryMapping.kt`, one layer
 * further out.
 *
 * The 12 DTOs with no core equivalent (`RegisterRequestDto`,
 * `RegisterResponseDto`, `TransferResultDto`, `ShareResultDto`,
 * `BlocksRequestDto`, `BlocksResponseDto`, `ErrorDto`) are intentionally not
 * covered here - core has no wire shape for them, so they keep using
 * kotlinx.serialization directly.
 *
 * Decode functions throw on malformed bytes (core's `ParseException`) rather
 * than falling back silently - a bad request body should surface as a 4xx/5xx
 * through the existing Ktor error handling, not be swallowed.
 */
expect object WireCodec {
    fun encodeDeviceInfo(dto: DeviceInfoDto): ByteArray
    fun decodeDeviceInfo(bytes: ByteArray): DeviceInfoDto

    fun encodePairRequest(dto: PairRequestDto): ByteArray
    fun decodePairRequest(bytes: ByteArray): PairRequestDto

    fun encodePairConfirm(dto: PairConfirmDto): ByteArray
    fun decodePairConfirm(bytes: ByteArray): PairConfirmDto

    fun encodePairRevoke(dto: PairRevokeDto): ByteArray
    fun decodePairRevoke(bytes: ByteArray): PairRevokeDto

    fun encodePairResult(dto: PairResultDto): ByteArray
    fun decodePairResult(bytes: ByteArray): PairResultDto

    fun encodeShareInvite(dto: ShareInviteDto): ByteArray
    fun decodeShareInvite(bytes: ByteArray): ShareInviteDto

    fun encodeShareAuthorize(dto: ShareAuthorizeDto): ByteArray
    fun decodeShareAuthorize(bytes: ByteArray): ShareAuthorizeDto

    fun encodeShareLeave(dto: ShareLeaveDto): ByteArray
    fun decodeShareLeave(bytes: ByteArray): ShareLeaveDto

    fun encodeIndexResponse(dto: IndexResponseDto): ByteArray
    fun decodeIndexResponse(bytes: ByteArray): IndexResponseDto

    fun encodeTransferRequest(dto: TransferRequestDto): ByteArray
    fun decodeTransferRequest(bytes: ByteArray): TransferRequestDto

    fun encodeTransferAccept(dto: TransferAcceptDto): ByteArray
    fun decodeTransferAccept(bytes: ByteArray): TransferAcceptDto

    fun encodeTransferChunkAck(dto: TransferChunkAckDto): ByteArray
    fun decodeTransferChunkAck(bytes: ByteArray): TransferChunkAckDto

    fun encodeTransferCancel(dto: TransferCancelDto): ByteArray
    fun decodeTransferCancel(bytes: ByteArray): TransferCancelDto
}