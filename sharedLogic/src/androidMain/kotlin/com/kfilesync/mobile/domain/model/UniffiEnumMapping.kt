package com.kfilesync.mobile.domain.model

/**
 * Drift-detection mapping between mobile's hand-written "pure" enums (no
 * payload, no persistence-shaped extras — see 任务2 plan) and the
 * UniFFI-generated `uniffi.kfilesync_core.*` enums they're meant to mirror
 * 1:1 (ADR-002/016/017 lock these variant sets).
 *
 * A literal `expect`/`actual enum class` delegation (the plan's original
 * wording) isn't possible: UniFFI emits SCREAMING_SNAKE_CASE member names
 * (`READ_ONLY`, `MAC_OS`, ...) while mobile's hand-written enums use
 * PascalCase (`ReadOnly`, `MacOS`, ...), and Kotlin's `actual enum class`
 * requires exact member-name matches. Renaming mobile's enums to match
 * would ripple through every call site and wire-format string for no
 * behavioural gain.
 *
 * Instead, these exhaustive `when` expressions give the same drift
 * detection the plan wanted: if core adds/removes/renames a variant, the
 * corresponding `when` here stops compiling, failing the build instead of
 * silently drifting. [UniffiEnumMappingTest] round-trips every mobile value
 * through `toUniffi()`/`toMobile()` to also catch a mapping that compiles
 * but is wrong (e.g. two variants swapped).
 *
 * Android-only: `uniffi.kfilesync_core` symbols don't exist on iOS.
 */

fun DevicePlatform.toUniffi(): uniffi.kfilesync_core.DevicePlatform = when (this) {
    DevicePlatform.Windows -> uniffi.kfilesync_core.DevicePlatform.WINDOWS
    DevicePlatform.MacOS -> uniffi.kfilesync_core.DevicePlatform.MAC_OS
    DevicePlatform.Linux -> uniffi.kfilesync_core.DevicePlatform.LINUX
    DevicePlatform.Android -> uniffi.kfilesync_core.DevicePlatform.ANDROID
    DevicePlatform.IOS -> uniffi.kfilesync_core.DevicePlatform.IOS
}

fun uniffi.kfilesync_core.DevicePlatform.toMobile(): DevicePlatform = when (this) {
    uniffi.kfilesync_core.DevicePlatform.WINDOWS -> DevicePlatform.Windows
    uniffi.kfilesync_core.DevicePlatform.MAC_OS -> DevicePlatform.MacOS
    uniffi.kfilesync_core.DevicePlatform.LINUX -> DevicePlatform.Linux
    uniffi.kfilesync_core.DevicePlatform.ANDROID -> DevicePlatform.Android
    uniffi.kfilesync_core.DevicePlatform.IOS -> DevicePlatform.IOS
}

fun DeviceType.toUniffi(): uniffi.kfilesync_core.DeviceType = when (this) {
    DeviceType.Desktop -> uniffi.kfilesync_core.DeviceType.DESKTOP
    DeviceType.Mobile -> uniffi.kfilesync_core.DeviceType.MOBILE
}

fun uniffi.kfilesync_core.DeviceType.toMobile(): DeviceType = when (this) {
    uniffi.kfilesync_core.DeviceType.DESKTOP -> DeviceType.Desktop
    uniffi.kfilesync_core.DeviceType.MOBILE -> DeviceType.Mobile
}

fun EntryType.toUniffi(): uniffi.kfilesync_core.EntryType = when (this) {
    EntryType.File -> uniffi.kfilesync_core.EntryType.FILE
    EntryType.Directory -> uniffi.kfilesync_core.EntryType.DIRECTORY
}

fun uniffi.kfilesync_core.EntryType.toMobile(): EntryType = when (this) {
    uniffi.kfilesync_core.EntryType.FILE -> EntryType.File
    uniffi.kfilesync_core.EntryType.DIRECTORY -> EntryType.Directory
}

fun SharePermission.toUniffi(): uniffi.kfilesync_core.SharePermission = when (this) {
    SharePermission.ReadOnly -> uniffi.kfilesync_core.SharePermission.READ_ONLY
    SharePermission.ReadWrite -> uniffi.kfilesync_core.SharePermission.READ_WRITE
}

fun uniffi.kfilesync_core.SharePermission.toMobile(): SharePermission = when (this) {
    uniffi.kfilesync_core.SharePermission.READ_ONLY -> SharePermission.ReadOnly
    uniffi.kfilesync_core.SharePermission.READ_WRITE -> SharePermission.ReadWrite
}

fun TransferDirection.toUniffi(): uniffi.kfilesync_core.TransferDirection = when (this) {
    TransferDirection.Outgoing -> uniffi.kfilesync_core.TransferDirection.OUTGOING
    TransferDirection.Incoming -> uniffi.kfilesync_core.TransferDirection.INCOMING
}

fun uniffi.kfilesync_core.TransferDirection.toMobile(): TransferDirection = when (this) {
    uniffi.kfilesync_core.TransferDirection.OUTGOING -> TransferDirection.Outgoing
    uniffi.kfilesync_core.TransferDirection.INCOMING -> TransferDirection.Incoming
}

fun TransferState.toUniffi(): uniffi.kfilesync_core.TransferState = when (this) {
    TransferState.Pending -> uniffi.kfilesync_core.TransferState.PENDING
    TransferState.Requested -> uniffi.kfilesync_core.TransferState.REQUESTED
    TransferState.Active -> uniffi.kfilesync_core.TransferState.ACTIVE
    TransferState.Paused -> uniffi.kfilesync_core.TransferState.PAUSED
    TransferState.Verifying -> uniffi.kfilesync_core.TransferState.VERIFYING
    TransferState.Completed -> uniffi.kfilesync_core.TransferState.COMPLETED
    TransferState.Failed -> uniffi.kfilesync_core.TransferState.FAILED
    TransferState.Cancelled -> uniffi.kfilesync_core.TransferState.CANCELLED
}

fun uniffi.kfilesync_core.TransferState.toMobile(): TransferState = when (this) {
    uniffi.kfilesync_core.TransferState.PENDING -> TransferState.Pending
    uniffi.kfilesync_core.TransferState.REQUESTED -> TransferState.Requested
    uniffi.kfilesync_core.TransferState.ACTIVE -> TransferState.Active
    uniffi.kfilesync_core.TransferState.PAUSED -> TransferState.Paused
    uniffi.kfilesync_core.TransferState.VERIFYING -> TransferState.Verifying
    uniffi.kfilesync_core.TransferState.COMPLETED -> TransferState.Completed
    uniffi.kfilesync_core.TransferState.FAILED -> TransferState.Failed
    uniffi.kfilesync_core.TransferState.CANCELLED -> TransferState.Cancelled
}