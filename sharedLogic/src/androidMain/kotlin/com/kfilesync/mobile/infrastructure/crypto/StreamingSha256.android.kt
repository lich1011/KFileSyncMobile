package com.kfilesync.mobile.infrastructure.crypto

import java.security.MessageDigest

/**
 * Android actual: thin wrapper around 'MessageDigest.getInstance("SHA-256")'.
 * Reset uses the documented 'digest()' + 'reset()' flow - calling 'digest()'
 * already resets implicitly, but we still call 'reset()' for clarity in case
 * a caller invokes [reset] without ever calling [finalize].
 */
actual class StreamingSha256 {

    private val md: MessageDigest = MessageDigest.getInstance("SHA-256")

    actual fun update(data: ByteArray, offset: Int, length: Int) {
        md.update(data, offset, length)
    }

    actual fun finalize(): ByteArray = md.digest()

    actual fun reset() {
        md.reset()
    }
}