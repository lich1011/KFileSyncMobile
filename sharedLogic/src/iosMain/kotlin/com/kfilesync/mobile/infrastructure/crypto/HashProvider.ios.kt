package com.kfilesync.mobile.infrastructure.crypto

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * iOS `actual` for [HashProvider].
 *
 * sha256 -> CommonCrypto's CC_SHA256 (hardware-accelerated on every iOS
 * device, no entitlement required).
 * blake3 -> Phase 2 (T2.3) - cross-compiled blake3 C library via cinterop.
 *
 * Implementation note: CommonCrypto's signatures are typed in Swift as
 * `UnsafePointer<UInt8>` / `UnsafeMutablePointer<UInt8>`; Kotlin/Native models
 * the same pointers as `CPointer<UByteVar>`. Our [ByteArray] pinning yields
 * `CPointer<ByteVar>` so we reinterpret each pinned pointer once at the call
 * site. This is safe - `Byte` and `UByte` share representation.
 */
@OptIn(ExperimentalForeignApi::class)
actual object HashProvider {

    actual fun blake3(data: ByteArray): ByteArray {
        TODO("Phase 2 T2.3 - wire blake3 cinterop C library")
    }

    actual fun sha256(data: ByteArray): ByteArray {
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        data.usePinned { input ->
            digest.usePinned { output ->
                val inPtr: CPointer<ByteVar> = input.addressOf(0)
                val outPtr: CPointer<ByteVar> = output.addressOf(0)
                CC_SHA256(
                    inPtr.reinterpret<UByteVar>(),
                    data.size.toUInt(),
                    outPtr.reinterpret<UByteVar>()
                )
            }
        }
        return digest
    }
}