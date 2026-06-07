package com.kfilesync.mobile.infrastructure.crypto

/**
 * PEM / DER conversion utilities.
 *
 * Extracted from [HttpServer], [PinnedTrustSnapshot], and [IosSecIdentityBridge]
 * where identical private copies existed. All callers now use these shared
 * functions.
 */
object PemUtils {
    /**
     * Strip PEM headers/footers and base64-decode the body into raw DER bytes.
     *
     * Works for any PEM-wrapped object (CERTIFICATE, PUBLIC KEY, PRIVATE KEY, etc.).
     * Returns null if the input contains no recognisable PEM block.
     */
    fun pemToDer(pem: String): ByteArray? {
        val stripped = pem.lines()
            .filter { !it.startsWith("-----") }
            .joinToString("")
            .trim()
        if (stripped.isEmpty()) return null
        return Base64Codec.decode(stripped)
    }
}
