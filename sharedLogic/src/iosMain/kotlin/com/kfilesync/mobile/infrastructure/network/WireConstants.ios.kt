package com.kfilesync.mobile.infrastructure.network

/**
 * iOS actual for [WireConstants], matching `KFileSyncCore`'s `protocol/routes.rs`
 * and `protocol/headers.rs` verbatim until iOS links the KFileSyncCore XCFramework.
 */
actual object WireConstants {
    actual val apiPrefix: String = "/api/lansync/v1"
    actual val routeInfo: String = "/api/lansync/v1/info"
    actual val routeHealthz: String = "/api/lansync/v1/healthz"
    actual val routeRegister: String = "/api/lansync/v1/register"
    actual val routePairRequest: String = "/api/lansync/v1/pair/request"
    actual val routePairConfirm: String = "/api/lansync/v1/pair/confirm"
    actual val routePairRevoke: String = "/api/lansync/v1/pair/revoke"
    actual val routeShareInvite: String = "/api/lansync/v1/share/invite"
    actual val routeShareAuthorize: String = "/api/lansync/v1/share/authorize"
    actual val routeShareLeave: String = "/api/lansync/v1/share/leave"
    actual val routeSyncIndex: String = "/api/lansync/v1/sync/index"
    actual val routeTransferRequest: String = "/api/lansync/v1/transfer/request"
    actual val routeTransferChunkPrefix: String = "/api/lansync/v1/transfer/"
    actual val routeTransferCancel: String = "/api/lansync/v1/transfer/cancel"
    actual val defaultPort: Int = 53317
    actual val headerDeviceId: String = "X-Device-Id"
    actual val headerTimestamp: String = "X-Timestamp"
    actual val headerNonce: String = "X-Nonce"
    actual val headerFingerprint: String = "X-Fingerprint"
    actual val headerChunkHash: String = "X-Chunk-Hash"
    actual val timestampUnitIsMillis: Boolean = true
}
