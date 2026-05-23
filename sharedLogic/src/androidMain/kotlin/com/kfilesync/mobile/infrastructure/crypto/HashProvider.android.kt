package com.kfilesync.mobile.infrastructure.crypto

import java.security.MessageDigest

/**
 * Android `actual` for [HashProvider].
 *
 * sha256 -> java.security.MessageDigest (always available, hardware-accelerated
 * on every device shipped after ~Android 8).
 * blake3 -> Phase 2 (T2.3) brings in a blake3-jni or pure-Kotlin implementation;
 * today the implementation throws so callers can already wire to it
 * without forking on platform.
 */
actual object HashProvider {

    actual fun blake3(data: ByteArray): ByteArray {
        TODO("Phase 2 T2.3 - wire blake3-jni / pure-Kotlin implementation")
    }

    actual fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}