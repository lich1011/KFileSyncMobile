package com.kfilesync.mobile.infrastructure.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Thin wrapper around `kotlin.io.encoding.Base64` so the rest of the code
 * doesn't have to scatter `@OptIn(ExperimentalEncodingApi::class)` everywhere.
 *
 * Uses the standard (RFC 4648 §4) alphabet — same as the desktop client's
 * `base64::encode` / `base64::decode` defaults.
 */
@OptIn(ExperimentalEncodingApi::class)
object Base64Codec {

    fun encode(bytes: ByteArray, length: Int = bytes.size): String =
        Base64.encode(bytes, startIndex = 0, endIndex = length)

    fun decode(text: String): ByteArray = Base64.decode(text)
}