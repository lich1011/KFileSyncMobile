package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.db.KFileSyncDatabase
import com.kfilesync.mobile.domain.port.TrustBootstrapState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Config-table-backed [TrustBootstrapState]. The key
 * `trust.ever_paired` stores literal "1" once set; absent means "never paired".
 *
 * Used by the TLS pinning trust managers to decide whether to allow the
 * pre-pairing bootstrap handshake. Re-checked on every TLS handshake but
 * the read is a single indexed SELECT, and the value is also cached in a
 * volatile field once the flag flips true (it never reverts).
 */
class SqlDelightTrustBootstrapState(
    private val db: KFileSyncDatabase
) : TrustBootstrapState {

    @Volatile
    private var cached: Boolean = false

    override suspend fun hasEverPaired(): Boolean = withContext(Dispatchers.Default) {
        if (cached) return@withContext true
        val v = try {
            db.configQueries.get(KEY).executeAsOneOrNull()
        } catch (t: Throwable) {
            Napier.w("TrustBootstrapState.hasEverPaired read failed: ${t.message}")
            null
        }
        val flag = v == "1"
        if (flag) cached = true
        flag
    }

    override suspend fun markPaired() = withContext(Dispatchers.Default) {
        if (cached) return@withContext
        try {
            db.configQueries.set(KEY, "1")
            cached = true
            Napier.i("TrustBootstrapState: marked ever-paired = true (bootstrap window closed)")
        } catch (t: Throwable) {
            Napier.w("TrustBootstrapState.markPaired write failed: ${t.message}")
        }
    }

    /**
     * Synchronous cached read for the TLS handshake hot path. Returns the
     * last value pulled from the DB; defaults to false until [hasEverPaired]
     * has been called at least once. Callers MUST have warmed the cache at
     * boot (the bootstrap path does this in `SecurityHandler.register`).
     */
    fun snapshot(): Boolean = cached

    companion object {
        const val KEY: String = "trust.ever_paired"
    }
}