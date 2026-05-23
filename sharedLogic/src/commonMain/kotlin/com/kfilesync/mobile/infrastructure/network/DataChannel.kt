package com.kfilesync.mobile.infrastructure.network

/**
 * High-throughput TLS/TCP data channel used for chunked file transfer
 * (separate from the JSON HTTPS control plane to avoid head-of-line blocking).
 *
 * Phase 2 (T2.3) brings this up with backpressure, resume offsets, and
 * per-chunk BLAKE3 verification. Kept as a placeholder so DI can already
 * reference the type.
 */
class DataChannel {

    suspend fun send() {
        TODO("Phase 2 T2.3 - chunked file transfer over a dedicated TLS socket")
    }

    suspend fun receive() {
        TODO("Phase 2 T2.3 - chunked file receive with BLAKE3 verification")
    }
}