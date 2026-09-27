package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.DeviceType
import com.kfilesync.mobile.domain.model.Share
import com.kfilesync.mobile.domain.model.SharePermission
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.model.SyncMode
import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.PemUtils
import com.kfilesync.mobile.infrastructure.crypto.toHexLower

/**
 * Maps mobile's hand-written [Device]/[Share] aggregates to the UniFFI types
 * `evaluatePolicy` expects (Sprint 3 UniFFI migration, Android-only - see
 * [UniffiFileEntryMapping.kt] for the equivalent [FileEntry][com.kfilesync.mobile.domain.model.FileEntry] mapping).
 */
fun Device.toUniffi(): uniffi.kfilesync_core.Device = uniffi.kfilesync_core.Device(
    id = id.value,
    alias = alias,
    deviceType = deviceType.toUniffi(),
    platform = platform.toUniffi(),
    state = state.toUniffi(),
    certFingerprintHex = certificatePem?.let { pemToFingerprintHexOrNull(it) }
)

/**
 * Derives the SHA-256 fingerprint hex of a stored PEM certificate, matching
 * [com.kfilesync.mobile.infrastructure.network.HttpServer]'s existing
 * `pairedDevices` fingerprint derivation used for anti-replay's `X-Fingerprint`
 * cross-check. Swallows malformed PEM (never expected in practice - only a
 * successfully [Device.confirmPairing]'d cert reaches here - but a bad stored
 * value shouldn't crash policy evaluation, which doesn't otherwise depend on
 * this field).
 */
private fun pemToFingerprintHexOrNull(pem: String): String? =
    runCatching { PemUtils.pemToDer(pem)?.let { HashProvider.sha256(it).toHexLower() } }.getOrNull()

fun DeviceState.toUniffi(): uniffi.kfilesync_core.DeviceState = when (this) {
    DeviceState.Discovered -> uniffi.kfilesync_core.DeviceState.DISCOVERED
    DeviceState.Paired -> uniffi.kfilesync_core.DeviceState.PAIRED
    DeviceState.Revoked -> uniffi.kfilesync_core.DeviceState.REVOKED
}

fun DeviceType.toUniffi(): uniffi.kfilesync_core.DeviceType = when (this) {
    DeviceType.Desktop -> uniffi.kfilesync_core.DeviceType.DESKTOP
    DeviceType.Mobile -> uniffi.kfilesync_core.DeviceType.MOBILE
}

fun DevicePlatform.toUniffi(): uniffi.kfilesync_core.DevicePlatform = when (this) {
    DevicePlatform.Windows -> uniffi.kfilesync_core.DevicePlatform.WINDOWS
    DevicePlatform.MacOS -> uniffi.kfilesync_core.DevicePlatform.MAC_OS
    DevicePlatform.Linux -> uniffi.kfilesync_core.DevicePlatform.LINUX
    DevicePlatform.Android -> uniffi.kfilesync_core.DevicePlatform.ANDROID
    DevicePlatform.IOS -> uniffi.kfilesync_core.DevicePlatform.IOS
}

fun Share.toUniffi(): uniffi.kfilesync_core.Share = uniffi.kfilesync_core.Share(
    id = id.value,
    name = name,
    syncMode = syncMode.toUniffi(),
    defaultPermission = permission.toUniffi(),
    status = status.toUniffi()
)

fun SyncMode.toUniffi(): uniffi.kfilesync_core.SyncMode = when (this) {
    SyncMode.OneWayPush -> uniffi.kfilesync_core.SyncMode.SEND_ONLY
    SyncMode.OneWayPull -> uniffi.kfilesync_core.SyncMode.RECEIVE_ONLY
    SyncMode.TwoWay -> uniffi.kfilesync_core.SyncMode.TWO_WAY
}

fun ShareStatus.toUniffi(): uniffi.kfilesync_core.ShareStatus = when (this) {
    ShareStatus.Pending -> uniffi.kfilesync_core.ShareStatus.PENDING
    ShareStatus.Active -> uniffi.kfilesync_core.ShareStatus.ACTIVE
    ShareStatus.Paused -> uniffi.kfilesync_core.ShareStatus.PAUSED
    ShareStatus.Left -> uniffi.kfilesync_core.ShareStatus.LEFT
}

fun SharePermission.toUniffi(): uniffi.kfilesync_core.SharePermission = when (this) {
    SharePermission.ReadOnly -> uniffi.kfilesync_core.SharePermission.READ_ONLY
    SharePermission.ReadWrite -> uniffi.kfilesync_core.SharePermission.READ_WRITE
}