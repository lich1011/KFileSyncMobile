package com.kfilesync.mobile.infrastructure.crypto

import java.security.SecureRandom

/**
 * Android 'actual' of [SecureRng] - delegates to [java.security.SecureRandom],
 * which is seeded from the kernel CSPRNG (/dev/urandom) on every supported
 * Android version. Thread-safe; a single instance is reused.
 */
private val rng: SecureRandom = SecureRandom()

actual object SecureRng {

    actual fun nextBytes(out: ByteArray) {
        rng.nextBytes(out)
    }
}