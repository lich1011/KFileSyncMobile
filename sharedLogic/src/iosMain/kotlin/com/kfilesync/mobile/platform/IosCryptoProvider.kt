package com.kfilesync.mobile.platform

/**
 * iOS 'actual' for 'HashProvider' (design doc §6.4).
 *
 * Phase 1 (T1.6) ships:
 * blake3 - cross-compiled BLAKE3 C library, called via cinterop
 * sha256 - CommonCrypto.CC_SHA256 (or CryptoKit.SHA256 once we drop iOS 12)
 *
 * Becomes 'actual object HashProvider { ... }' once T1.6 lands the expect decl.
 */
object IosCryptoProvider {

    fun blake3(data: ByteArray): ByteArray = TODO("Phase 1 T1.6")

    fun sha256(data: ByteArray): ByteArray = TODO("Phase 1 T1.6")
}