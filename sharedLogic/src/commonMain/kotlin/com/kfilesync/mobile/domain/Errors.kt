package com.kfilesync.mobile.domain

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.ShareId

/**
 * Layered error hierarchy (design doc §6.6).
 *
 * Domain  – pure business semantics, safe to surface to UI.
 * Infra   – technical details, NOT shown to UI.
 * App     – unified envelope; wraps the other two for callers.
 *
 * Kept side-by-side in one file so the three layers stay visibly in sync.
 */

// ------- Domain layer -------

sealed class DomainError : Exception() {

    data class DeviceNotFound(val deviceId: DeviceId) : DomainError() {
        override val message: String = "device not found: ${deviceId.value}"
    }

    data class DeviceNotTrusted(val deviceId: DeviceId) : DomainError() {
        override val message: String = "device not trusted: ${deviceId.value}"
    }

    data class InvalidStateTransition(val reason: String) : DomainError() {
        override val message: String = "invalid state transition: $reason"
    }

    data class ShareNotFound(val shareId: ShareId) : DomainError() {
        override val message: String = "share not found: ${shareId.value}"
    }

    data class PermissionDenied(val reason: String) : DomainError() {
        override val message: String = "permission denied: $reason"
    }

    data class VersionConflict(val filePath: String) : DomainError() {
        override val message: String = "version conflict: $filePath"
    }

    data class IntegrityError(val reason: String) : DomainError() {
        override val message: String = "integrity error: $reason"
    }
}

// ------- Infrastructure Layer -------

sealed class InfraError : Exception() {
    data class Database(override val cause: Throwable) : InfraError()
    data class Network(override val cause: Throwable) : InfraError()
    data class Tls(override val cause: Throwable) : InfraError()
    data class KeyStoreError(override val message: String) : InfraError()
}

// ------- Application layer (unified envelope) -------

sealed class AppError : Exception() {

    data class Domain(val error: DomainError) : AppError() {
        override val message: String? get() = error.message
    }

    /** Infra errors are wrapped here; UI sees a generic "internal error" by design. */
    data class Internal(val error: InfraError) : AppError() {
        override val message: String = "internal error"
    }
}