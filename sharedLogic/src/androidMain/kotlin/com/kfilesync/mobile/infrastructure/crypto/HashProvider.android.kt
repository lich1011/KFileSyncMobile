package com.kfilesync.mobile.infrastructure.crypto

import java.security.MessageDigest

/**
 * Android 'actual' for [HashProvider].
 *
 * 'sha256' uses 'MessageDigest.getInstance("SHA-256")' - hardware-accelerated
 * via Conscrypt / AndroidKeyStore on Android 8+. 'blake3' lives in commonMain
 * as a pure-Kotlin implementation (see [Blake3Hasher]) so no actual is needed
 * here.
 */
actual object HashProvider {

    actual fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}