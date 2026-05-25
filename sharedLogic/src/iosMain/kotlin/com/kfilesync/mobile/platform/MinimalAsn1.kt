package com.kfilesync.mobile.platform

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Minimal ASN.1 DER encoder - just enough to mint a self-signed X.509 v3
 * certificate from an externally-supplied EC public key + ECDSA signature
 * (T1.1, iOS path).
 *
 * Why hand-rolled? CommonCrypto / Security.framework can *parse* X.509 with
 * `SecCertificateCreateWithData` but they don't offer a *builder* on iOS.
 * Pulling in OpenSSL or BoringSSL via cinterop just for cert minting is
 * heavier than the 200-LOC subset we actually need.
 *
 * The encoder is intentionally permissive and trusts its callers - it
 * implements only:
 * - INTEGER, OCTET STRING, BOOLEAN, NULL, OBJECT IDENTIFIER
 * - SEQUENCE, SET, BIT STRING (with 0 unused bits)
 * - UTCTime, PrintableString, UTF8String
 * - context-tagged elements (used for v3 extensions)
 *
 * Output is raw DER bytes. PEM wrapping is the caller's job.
 */
@OptIn(ExperimentalForeignApi::class)
internal object MinimalAsn1 {

    // ---- Tags ----
    private const val TAG_BOOLEAN: Byte = 0x01
    private const val TAG_INTEGER: Byte = 0x02
    private const val TAG_BIT_STRING: Byte = 0x03
    private const val TAG_OCTET_STRING: Byte = 0x04
    private const val TAG_NULL: Byte = 0x05
    private const val TAG_OID: Byte = 0x06
    private const val TAG_UTF8_STRING: Byte = 0x0C
    private const val TAG_PRINTABLE_STRING: Byte = 0x13
    private const val TAG_UTC_TIME: Byte = 0x17
    private const val TAG_SEQUENCE: Byte = 0x30
    private const val TAG_SET: Byte = 0x31

    /** Wrap a tag + length around a body. */
    private fun wrap(tag: Byte, body: ByteArray): ByteArray {
        val len = encodeLength(body.size)
        val out = ByteArray(1 + len.size + body.size)
        out[0] = tag
        len.copyInto(out, destinationOffset = 1)
        body.copyInto(out, destinationOffset = 1 + len.size)
        return out
    }

    private fun encodeLength(len: Int): ByteArray {
        if (len < 128) return byteArrayOf(len.toByte())
        // Long form: 0x80 | byte-count, then big-endian length bytes.
        val bytes = mutableListOf<Byte>()
        var n = len
        while (n > 0) {
            bytes.add(0, (n and 0xFF).toByte())
            n = n ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    fun sequence(vararg items: ByteArray): ByteArray = wrap(TAG_SEQUENCE, items.fold(ByteArray(0)) { acc, b -> acc + b })
    fun set(vararg items: ByteArray): ByteArray = wrap(TAG_SET, items.fold(ByteArray(0)) { acc, b -> acc + b })

    /** Context-specific constructed tag [n] wrapping [body] (used for v3 extensions [3]). */
    fun ctxConstructed(n: Int, body: ByteArray): ByteArray {
        require(n in 0..30)
        return wrap((0xA0 or n).toByte(), body)
    }

    /** ASN.1 INTEGER from an arbitrary big-endian byte array. Adds a leading 0 if the high bit is set. */
    fun integer(bytes: ByteArray): ByteArray {
        // Strip leading zeros except for one when value is zero.
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte() && (bytes[start + 1].toInt() and 0x80) == 0) {
            start++
        }
        val trimmed = bytes.copyOfRange(start, bytes.size)
        // Re-pad with leading 0 if the high bit is set (so it stays unsigned).
        if ((trimmed[0].toInt() and 0x80) != 0) {
            return wrap(TAG_INTEGER, byteArrayOf(0) + trimmed)
        }
        return wrap(TAG_INTEGER, trimmed)
    }

    fun integer(value: Long): ByteArray {
        val raw = ByteArray(8) { i -> (value ushr ((7 - i) * 8) and 0xFF).toByte() }
        return integer(raw)
    }

    fun boolean(b: Boolean): ByteArray = wrap(TAG_BOOLEAN, byteArrayOf(if (b) 0xFF.toByte() else 0))
    fun nullValue(): ByteArray = wrap(TAG_NULL, ByteArray(0))
    fun octetString(bytes: ByteArray): ByteArray = wrap(TAG_OCTET_STRING, bytes)
    fun utf8String(s: String): ByteArray = wrap(TAG_UTF8_STRING, s.encodeToByteArray())
    fun printableString(s: String): ByteArray = wrap(TAG_PRINTABLE_STRING, s.encodeToByteArray())

    /** BIT STRING wrapping [bytes] with 0 unused bits. */
    fun bitString(bytes: ByteArray): ByteArray = wrap(TAG_BIT_STRING, byteArrayOf(0) + bytes)

    /** ASN.1 OID from dot-notation, e.g. "1.2.840.10045.4.3.2" (ecdsa-with-SHA256). */
    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "OID must have at least two arcs" }
        val first = parts[0] * 40 + parts[1]
        val out = mutableListOf<Byte>()
        out.add(first.toByte())
        for (i in 2 until parts.size) {
            val arc = parts[i]
            val bits = encodeBase128(arc)
            out.addAll(bits.toList())
        }
        return wrap(TAG_OID, out.toByteArray())
    }

    private fun encodeBase128(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        val bytes = mutableListOf<Byte>()
        var n = value
        bytes.add(0, (n and 0x7F).toByte())
        n = n ushr 7
        while (n > 0) {
            bytes.add(0, ((n and 0x7F) or 0x80).toByte())
            n = n ushr 7
        }
        return bytes.toByteArray()
    }

    /** UTCTime: YYMMDDHHMMSSZ (works for years 1950-2049, fine for our 10y self-signed certs). */
    fun utcTime(yyMMddHHmmssZ: String): ByteArray {
        require(yyMMddHHmmssZ.length == 13 && yyMMddHHmmssZ.endsWith('Z'))
        return wrap(TAG_UTC_TIME, yyMMddHHmmssZ.encodeToByteArray())
    }
}