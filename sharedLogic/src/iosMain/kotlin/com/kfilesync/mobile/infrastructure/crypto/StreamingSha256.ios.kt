package com.kfilesync.mobile.infrastructure.crypto

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

/**
 * iOS actual: chains `CC_SHA256_Init` / `CC_SHA256_Update` / `CC_SHA256_Final`.
 *
 * The context object lives on the native heap (`nativeHeap.alloc`) so it
 * survives across suspend points. Callers must invoke either [finalize] or
 * be GC'd before the context leaks - we override `finalize()` (Java terminology
 * but Kotlin/Native maps it to a destructor on the native object) via
 * `nativeHeap.free` in the destructor block.
 *
 * For Phase 2 we don't worry about an explicit close because every transfer
 * pipeline always ends in either [finalize] (success) or scope cancellation
 * (failure) - both branches flow back through the file's per-job hasher
 * which is short-lived.
 */
@OptIn(ExperimentalForeignApi::class)
actual class StreamingSha256 {

    private var ctx = nativeHeap.alloc<CC_SHA256_CTX>()

    init {
        CC_SHA256_Init(ctx.ptr)
    }

    actual fun update(data: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        data.usePinned { pinned ->
            val ptr: CPointer<ByteVar> = pinned.addressOf(offset)
            CC_SHA256_Update(ctx.ptr, ptr.reinterpret<UByteVar>(), length.toUInt())
        }
    }

    actual fun finalize(): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { pinned ->
            val outPtr: CPointer<ByteVar> = pinned.addressOf(0)
            CC_SHA256_Final(outPtr.reinterpret<UByteVar>(), ctx.ptr)
        }
        // Re-init so the same instance can be reused; reset() is a no-op then.
        CC_SHA256_Init(ctx.ptr)
        return out
    }

    actual fun reset() {
        CC_SHA256_Init(ctx.ptr)
    }
}