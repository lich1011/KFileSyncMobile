package com.kfilesync.mobile.infrastructure.crypto

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS `actual` of [SecureRng] - delegates to `SecRandomCopyBytes` from
 * Security.framework, which is backed by `/dev/urandom` and reseeded from
 * the Secure Enclave when available. Equivalent to BSD's `arc4random_buf`
 * for cryptographic purposes.
 */
@OptIn(ExperimentalForeignApi::class)
actual object SecureRng {

    actual fun nextBytes(out: ByteArray) {
        if (out.isEmpty()) return
        val status = SecRandomCopyBytes(
            kSecRandomDefault,
            out.size.toULong(),
            out.refTo(0)
        )
        require(status == 0) { "SecRandomCopyBytes failed: $status" }
    }
}