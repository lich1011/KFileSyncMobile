package com.kfilesync.mobile.infrastructure.network

/**
 * Delegates to core's `uniffi.kfilesync_core.wireConstants()`, called exactly
 * once and cached - see the commonMain doc comment for why (ADR-018: each
 * call allocates a fresh `String` per field).
 */
actual object WireConstants {
    private val native: uniffi.kfilesync_core.WireConstants by lazy {
        uniffi.kfilesync_core.wireConstants()
    }

    actual val apiPrefix: String get() = native.apiPrefix
    actual val routeInfo: String get() = native.routeInfo
    actual val routeHealthz: String get() = native.routeHealthz
    actual val routeRegister: String get() = native.routeRegister
    actual val routePairRequest: String get() = native.routePairRequest
    actual val routePairConfirm: String get() = native.routePairConfirm
    actual val routePairRevoke: String get() = native.routePairRevoke
    actual val routeShareInvite: String get() = native.routeShareInvite
    actual val routeShareAuthorize: String get() = native.routeShareAuthorize
    actual val routeShareLeave: String get() = native.routeShareLeave
    actual val routeSyncIndex: String get() = native.routeSyncIndex
    actual val routeTransferRequest: String get() = native.routeTransferRequest
    actual val routeTransferChunkPrefix: String get() = native.routeTransferChunkPrefix
    actual val routeTransferCancel: String get() = native.routeTransferCancel
    actual val defaultPort: Int get() = native.defaultPort.toInt()
    actual val headerDeviceId: String get() = native.headerDeviceId
    actual val headerTimestamp: String get() = native.headerTimestamp
    actual val headerNonce: String get() = native.headerNonce
    actual val headerFingerprint: String get() = native.headerFingerprint
    actual val headerChunkHash: String get() = native.headerChunkHash
    actual val timestampUnitIsMillis: Boolean get() = native.timestampUnitIsMillis
}