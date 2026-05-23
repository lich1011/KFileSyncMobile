package com.kfilesync.mobile.domain.model

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
enum class DevicePlatform { Windows, MacOS, Linux, Android, IOS }

enum class DeviceType { Desktop, Mobile }

/**
 * Device aggregate root.
 * Phase 0: minimal placeholder. Phase 1 (T1.1) will expand this with the
 * full DeviceState sealed-class state machine and domain-event emission.
 */
data class Device(
    val id: DeviceId,
    val alias: String,
    val platform: DevicePlatform,
    val deviceType: DeviceType,
    val addresses: List<DeviceAddress> = emptyList(),
    val state: DeviceState = DeviceState.Discovered(Instant.fromEpochMilliseconds(0L))
)