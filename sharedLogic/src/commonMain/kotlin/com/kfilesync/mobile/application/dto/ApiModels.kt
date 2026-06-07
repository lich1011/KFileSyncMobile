package com.kfilesync.mobile.application.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format DTOs for the lansync v1 REST surface (design doc §7).
 *
 * Field naming follows the desktop's snake_case convention so the two
 * implementations can talk to each other byte-for-byte.
 *
 * Every field stays primitive - the on-the-wire format is intentionally
 * decoupled from our internal value objects (DeviceId, Fingerprint, ...)
 * so a future protocol revision can change either side independently.
 */

// ---------- /api/lansync/v1/info ----------

@Serializable
data class DeviceInfoDto(
    val protocol: String = "Lansync",
    val version: String = "1.0",
    @SerialName("device_id") val deviceId: String,
    val alias: String,
    @SerialName("device_type") val deviceType: String,      // "desktop" | "mobile"
    val platform: String,                                   // "windows" | "macos" | "linux" | "android" | "ios"
    val fingerprint: String,                                // SHA-256 hex of TLS cert
    val port: Int = 53317,
    val announce: Boolean = true
)

// ---------- /api/lansync/v1/register ----------

@Serializable
data class RegisterRequestDto(
    @SerialName("device_id") val deviceId: String,
    val alias: String,
    @SerialName("device_type") val deviceType: String,
    val platform: String,
    val fingerprint: String,
    val addresses: List<String> = emptyList()
)

@Serializable
data class RegisterResponseDto(
    val accepted: Boolean,
    val reason: String? = null
)

// --------- /api/lansync/v1/pair/* ---------

/**
 * `POST /pair/request` body.
 *
 * Security hardening (issue #10): we deliberately do NOT send a PIN here.
 * Sending the originator's PIN to the peer would let *anyone* who POSTs
 * `/pair/request` write the expected PIN on the receiver's side, bypassing
 * the out-of-band ceremony.
 *
 * Instead:
 * - Originator generates a PIN locally and displays it on its screen.
 * - Peer ALSO generates a PIN locally and displays it on its screen.
 * - User reads each side's PIN to the other (two-channel OOB).
 * - `/pair/confirm` then carries the peer-displayed PIN that the
 * originator typed; the peer validates against its own stored PIN.
 */
@Serializable
data class PairRequestDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("from_device_id") val fromDeviceId: String,
    @SerialName("from_alias") val fromAlias: String,
    @SerialName("from_fingerprint") val fromFingerprint: String,
    val nonce: String,
    @SerialName("expires_at") val expiresAtEpochMs: Long
)

@Serializable
data class PairConfirmDto(
    @SerialName("request_id") val requestId: String,
    val pin: String,
    @SerialName("certificate_pem") val certificatePem: String, // exchanged on confirmation
    // Sender's alias + platform, used so the receiver can record a useful
    // display name instead of the hardcoded "Peer" placeholder (issue #12).
    @SerialName("from_alias") val fromAlias: String = "",
    @SerialName("from_platform") val fromPlatform: String = ""
)

@Serializable
data class PairRevokeDto(
    @SerialName("device_id") val deviceId: String,
    val reason: String? = null
)

@Serializable
data class PairResultDto(
    val ok: Boolean,
    val error: String? = null,
    @SerialName("peer_certificate_pem" )val peerCertificatePem: String? =null
)

/// --------- /api/lansync/v1/transfer/* (Phase 2) ---------

/**
 * Outbound transfer request - sender sends this to the receiver's
 * `POST /transfer/request`. The receiver responds with [TransferAcceptDto]
 * after the user accepts in the UI (or auto-accepts for share-driven
 * transfers in Phase 4).
 */
@Serializable
data class TransferRequestDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("job_id") val jobId: String,
    @SerialName("from_device_id") val fromDeviceId: String,
    @SerialName("from_alias") val fromAlias: String,
    @SerialName("share_id") val shareId: String? = null, // null for direct send
    val files: List<TransferFileDto>
)

@Serializable
data class TransferFileDto(
    @SerialName("file_id") val fileId: String,
    val path: String,
    val size: Long,
    val sha256: String,
    @SerialName("chunk_size") val chunkSize: Int,
    @SerialName("chunk_hashes") val chunkHashes: List<String>
)

/**
 * Receiver's reply to `/transfer/request`. `accepted=true` allows the
 * sender to start streaming; `skipChunks` is the resume map:
 * `fileId -> [list of chunk indices already received and verified]`.
 * Sender skips any indices in the list. Empty map = full transfer.
 */
@Serializable
data class TransferAcceptDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("job_id") val jobId: String,
    val accepted: Boolean,
    val reason: String? = null,
    @SerialName("skip_chunks") val skipChunks: Map<String, List<Int>> = emptyMap()
)

/**
 * One chunk upload request (POST /transfer/chunks). The Phase 2 wire path
 * sends every chunk as a separate JSON request with a base64-encoded body
 * - fine for LAN throughput at the chunk sizes the strategy picks
 * (<= 16 MiB), and lets us keep the existing Ktor JSON pipeline. A binary
 * websocket path will replace this in Phase 5 if profiling justifies it.
 */
@Serializable
data class TransferChunkDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("job_id") val jobId: String,
    @SerialName("file_id") val fileId: String,
    @SerialName("chunk_index") val chunkIndex: Int,
    @SerialName("chunk_size") val chunkSize: Int,
    /** BLAKE3 hex of [dataB64] decoded to bytes - receiver verifies before persisting. */
    @SerialName("chunk_hash") val chunkHash: String,
    /** base64 of raw bytes (StdEncoding, no newlines). */
    @SerialName("data_b64") val dataB64: String
)

@Serializable
data class TransferChunkAckDto(
    val ok: Boolean,
    @SerialName("file_id") val fileId: String,
    @SerialName("chunk_index") val chunkIndex: Int,
    /** True when this chunk completed the whole file (receiver did SHA-256 + atomic move). */
    @SerialName("file_completed") val fileCompleted: Boolean = false,
    /** True when this chunk completed the whole job. */
    @SerialName("job_completed") val jobCompleted: Boolean = false,
    val error: String? = null
)

@Serializable
data class TransferCancelDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("job_id") val jobId: String,
    val reason: String? = null
)

@Serializable
data class TransferResultDto(
    val ok: Boolean,
    val error: String? = null
)

// --------- /api/lansync/v1/share/* ---------

@Serializable
data class ShareInviteDto(
    @SerialName("share_id") val shareId: String,
    @SerialName("share_name") val shareName: String,
    @SerialName("from_device_id") val fromDeviceId: String,
    val permission: String, // "read_only" | "read_write" | ...
    @SerialName("sync_mode") val syncMode: String // "one_way_push" | "one_way_pull" | ...
)

@Serializable
data class ShareAuthorizeDto(
    @SerialName("share_id") val shareId: String,
    @SerialName("device_id") val deviceId: String,
    val permission: String,
    @SerialName("authorized_by") val authorizedBy: String
)

@Serializable
data class ShareResultDto(
    val ok: Boolean,
    val error: String? = null
)

// ---------- /api/lansync/v1/sync/* ----------

@Serializable
data class IndexResponseDto(
    @SerialName("share_id") val shareId: String,
    val entries: List<FileEntryDto>
)

@Serializable
data class FileEntryDto(
    val path: String,
    @SerialName("entry_type") val entryType: String,        // "file" | "directory"
    val size: Long,
    @SerialName("modified_at") val modifiedAtEpochMs: Long? = null,
    @SerialName("modified_by") val modifiedBy: String? = null,
    @SerialName("version_vector") val versionVector: Map<String, Long>,
    val sha256: String? = null,
    val blocks: List<String> = emptyList(),
    val deleted: Boolean = false
)

@Serializable
data class BlocksRequestDto(
    @SerialName("share_id") val shareId: String,
    val paths: List<String>
)

@Serializable
data class BlocksResponseDto(
    val blocks: Map<String, String>                         // BLAKE3 hex -> base64(content) - Phase 4
)

// ---------- /api/lansync/v1/info error wrapper ----------

@Serializable
data class ErrorDto(
    val code: String,
    val message: String
)