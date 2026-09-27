package com.kfilesync.mobile.infrastructure.network

/**
 * Sprint 4 follow-up (ADR-018): mirrors core's `WireConstants` UniFFI record
 * (route paths, default port, anti-replay/chunk header names). The androidMain
 * `actual` calls `uniffi.kfilesync_core.wireConstants()` exactly once and
 * caches the result - ADR-018 warns that function allocates a fresh `String`
 * per field on every call, so callers must not call it per-request.
 *
 * `/sync/blocks` is NOT covered here - core has no field for it (no wire DTO
 * exists on the core side for that route), so `HttpServer.kt`/`HttpClient.kt`
 * keep that one path literal hardcoded. `/register`'s path IS covered
 * ([routeRegister]) even though its request/response bodies have no core
 * equivalent and stay hand-rolled kotlinx.serialization.
 */
expect object WireConstants {
    val apiPrefix: String
    val routeInfo: String
    val routeHealthz: String
    val routeRegister: String
    val routePairRequest: String
    val routePairConfirm: String
    val routePairRevoke: String
    val routeShareInvite: String
    val routeShareAuthorize: String
    val routeShareLeave: String
    val routeSyncIndex: String
    val routeTransferRequest: String
    val routeTransferChunkPrefix: String
    val routeTransferCancel: String
    val defaultPort: Int
    val headerDeviceId: String
    val headerTimestamp: String
    val headerNonce: String
    val headerFingerprint: String
    val headerChunkHash: String
    val timestampUnitIsMillis: Boolean
}