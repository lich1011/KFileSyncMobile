package com.kfilesync.mobile.application.identity

import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.port.DeviceIdentityProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * "Who am I?" - every running process needs a stable [LocalIdentity] so the
 * lansync v1 endpoints can echo it back to peers.
 *
 * Phase 0 used [InMemoryLocalIdentityProvider] with a hard-coded placeholder.
 * Phase 1 (T1.1) switches the live binding to [PlatformBackedLocalIdentityProvider]
 * which lazily derives the identity from [DeviceIdentityProvider] on first
 * access - so the first call hits the platform secure-key store and the
 * result is then cached for the lifetime of the process.
 *
 * The Phase 0 in-memory provider is kept around for tests and for the
 * pre-Phase-1 demo path.
 */
data class LocalIdentity(
    val deviceId: DeviceId,
    val alias: String,
    val platform: DevicePlatform,
    /**
     * SHA-256 of the device's TLS certificate DER (Phase 1 T1.1 onwards).
     * Used by the lansync v1 wire format for peer-side cert pinning.
     */
    val fingerprint: Fingerprint,
    /**
     * PEM-encoded self-signed certificate (Phase 1 T1.4). Sent to the peer
     * during the pairing handshake so they can pin our fingerprint.
     * Empty for the in-memory test provider.
     */
    val certificatePem: String = "",
    val port: Int = 53317
)

/** Provider port - kept tiny so the Ktor route handlers can depend on it directly. */
interface LocalIdentityProvider {
    fun current(): LocalIdentity
    fun updateAlias(newAlias: String) {}
}

/** Phase 0 / test default. Real persistence arrives via [PlatformBackedLocalIdentityProvider]. */
class InMemoryLocalIdentityProvider(
    private val identity: LocalIdentity
) : LocalIdentityProvider {
    override fun current(): LocalIdentity = identity
}

class PlatformBackedLocalIdentityProvider(
    private val identityProvider: DeviceIdentityProvider,
    private val platform: DevicePlatform,
    private val defaultAlias: String,
    private val port: Int = 53317,
    private val persistedAliasReader: (() -> String?)? = null
) : LocalIdentityProvider {

    @Volatile
    private var cached: LocalIdentity? = null

    private val mutex = Mutex()

    override fun updateAlias(newAlias: String) {
        val trimmed = newAlias.trim()
        if (trimmed.isBlank()) return
        cached = cached?.copy(alias = trimmed)
    }

    override fun current(): LocalIdentity {
        cached?.let { return it }
        return runBlocking {
            mutex.withLock {
                cached?.let { return@withLock it }
                Napier.i("PlatformBackedLocalIdentityProvider: bootstrapping identity")
                val effectiveAlias = runCatching { persistedAliasReader?.invoke() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultAlias
                val crypto = identityProvider.loadOrGenerate(effectiveAlias)
                val fresh = LocalIdentity(
                    deviceId = crypto.deviceId,
                    alias = effectiveAlias,
                    platform = platform,
                    fingerprint = crypto.fingerprint,
                    certificatePem = crypto.certificatePem,
                    port = port
                )
                cached = fresh
                fresh
            }
        }
    }
}