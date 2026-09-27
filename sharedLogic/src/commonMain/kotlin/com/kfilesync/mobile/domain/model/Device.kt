package com.kfilesync.mobile.domain.model

import com.kfilesync.mobile.domain.DomainError
import kotlin.time.Clock
import kotlin.time.Instant

/** Value object: a device's globally unique identifier (SHA-256 hex of TLS certificate DER). */
@kotlin.jvm.JvmInline
value class DeviceId(val value: String)

/** Value object: SHA-256 fingerprint, used to pin a peer's TLS certificate. */
@kotlin.jvm.JvmInline
value class Fingerprint(val hex: String)

/** Value object: a single network address advertised by a device. */
data class DeviceAddress(
    val host: String,
    val port: Int = 53317
)

/** Trust status enum value object. */
enum class TrustStatus { Discovered, Paired, Revoked }

/** Platform on which a device runs. */
enum class DevicePlatform {
    Windows, MacOS, Linux, Android, IOS;

    /** Serialize to the wire-format string used by the LanSync protocol. */
    fun toWire(): String = when (this) {
        Windows -> "windows"
        MacOS -> "macos"
        Linux -> "linux"
        Android -> "android"
        IOS -> "ios"
    }

    companion object {
        /** Deserialize from a wire-format string, defaulting to [Android]. */
        fun fromWire(value: String?): DevicePlatform = when (value?.lowercase()) {
            "windows" -> Windows
            "macos" -> MacOS
            "linux" -> Linux
            "android" -> Android
            "ios" -> IOS
            else -> Android
        }
    }
}

enum class DeviceType { Desktop, Mobile }

/**
 * Device aggregate root.
 *
 * Legal [state] transitions:
 * Discovered --confirmPairing--> Paired
 * Paired     --revoke---------> Revoked
 *
 * All other transitions return `Result.failure(InvalidStateTransition)`. Per-state
 * associated data that used to live on `DeviceState`'s sealed-class variants
 * (Sprint 3 UniFFI migration: `DeviceState` is now a plain enum mirroring
 * `uniffi.kfilesync_core.DeviceState`) is flattened onto this class instead.
 */
data class Device(
    val id: DeviceId,
    val alias: String,
    val platform: DevicePlatform,
    val deviceType: DeviceType,
    val addresses: List<DeviceAddress> = emptyList(),
    val state: DeviceState = DeviceState.Discovered,
    val discoveredAt: Instant = Instant.fromEpochMilliseconds(0L),
    val pairedAt: Instant? = null,
    val certificatePem: String? = null,
    val revokedAt: Instant? = null,
    val revokedReason: String? = null
) {
    fun confirmPairing(certificatePem: String, now: Instant = Clock.System.now()): Result<Device> =
        if (state == DeviceState.Discovered) {
            Result.success(copy(state = DeviceState.Paired, certificatePem = certificatePem, pairedAt = now))
        } else {
            Result.failure(DomainError.InvalidStateTransition("Only Discovered can be paired (was $state)"))
        }

    fun revoke(reason: String? = null, now: Instant = Clock.System.now()): Result<Device> =
        if (state == DeviceState.Paired) {
            Result.success(copy(state = DeviceState.Revoked, revokedAt = now, revokedReason = reason))
        } else {
            Result.failure(DomainError.InvalidStateTransition("Only Paired can be revoked (was $state)"))
        }
}