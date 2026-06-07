package com.kfilesync.mobile.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * iOS interop helpers - Kotlin/Native <-> Cocoa byte-buffer conversions used by
 * the Keychain adapter and the device-identity provider.
 *
 * Kept in their own file so the platform adapters don't each redefine the
 * same helpers (which would clash at link time on Kotlin/Native).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    val arr = this
    if (arr.isEmpty()) return NSData()
    return arr.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = arr.size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun String.toNSData(): NSData = this.encodeToByteArray().toNSData()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val len = this.length.toInt()
    if (len == 0) return ByteArray(0)
    val bytes = this.bytes!!.reinterpret<ByteVar>()
    return ByteArray(len) { i -> bytes[i] }
}