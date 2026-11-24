package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.db.KFileSyncDatabase
import com.kfilesync.mobile.domain.model.SyncMode
import com.kfilesync.mobile.domain.service.SyncPolicyId
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Surfaceable settings snapshot (T5.3).
 *
 * Used by the Settings screen ViewModel. Keep this tiny and serialisable -
 * Compose recomposition cost scales with field count.
 */
data class SettingsSnapshot(
    val alias: String,
    val deviceIdShort: String,
    val fingerprintFormatted: String,
    val syncPolicy: SyncPolicyId,
    val cacheSizeBytes: Long,
    val downloadDirLocator: String? = null,
    val downloadDirDisplay: String = DEFAULT_DOWNLOAD_DIR_DISPLAY,
    val isDefaultDownloadDir: Boolean = true
) {
    companion object {
        const val DEFAULT_DOWNLOAD_DIR_DISPLAY: String = "Downloads/KFileSync"

        val EMPTY = SettingsSnapshot(
            alias = "",
            deviceIdShort = "",
            fingerprintFormatted = "",
            syncPolicy = SyncPolicyId.Default,
            cacheSizeBytes = 0L,
            downloadDirLocator = null,
            downloadDirDisplay = DEFAULT_DOWNLOAD_DIR_DISPLAY,
            isDefaultDownloadDir = true
        )
    }
}

/**
 * Driving port: settings tab use cases (T5.3).
 *
 * - The alias is the friendly name peers see; persisted in the `config`
 * table under key `device.alias`.
 * - The sync policy id is persisted under `sync.policy` and read at app
 * start by the WorkManager scheduler.
 * - The fingerprint comes from [LocalIdentityProvider] - read-only.
 * - Cache size is observed live so the user can see the storage maintenance
 * sweep's results.
 * - Download directory defaults to the device's Downloads folder (`null` locator)
 *   and may be overridden by a user-chosen SAF tree URI / folder path.
 */
interface SettingsAppService {
    fun observeSettings(): Flow<SettingsSnapshot>
    suspend fun refresh()
    suspend fun setAlias(alias: String): Result<Unit>
    suspend fun setSyncPolicy(id: SyncPolicyId): Result<Unit>
    suspend fun clearCache(): Result<Long>
    suspend fun setDownloadDirectory(locator: String): Result<Unit> = Result.success(Unit)
    suspend fun resetDownloadDirectory(): Result<Unit> = Result.success(Unit)
    fun currentDownloadDirectory(): String? = null
}

/**
 * Phase 5 implementation. Lives in `application.service` (not domain)
 * because it touches the [KFileSyncDatabase] directly - `config` is a
 * cross-cutting K/V table, not an aggregate. Reads + writes through
 * SQLDelight queries are short and serialisable, so we run them on
 * [Dispatchers.IO] (Android) / Default (iOS).
 *
 * The settings flow is hot - 'Phase 5 T5.3' ViewModels collect it
 * directly and drive Compose state. We re-emit by calling [refresh] after
 * every write so the UI sees the new value without needing a separate
 * `getX()` call.
 */
class SettingsServiceImpl(
    private val database: KFileSyncDatabase,
    private val storage: StorageMaintenanceService,
    private val localIdentityProvider: LocalIdentityProvider
) : SettingsAppService {

    private val snapshotFlow = MutableStateFlow(EMPTY)
    private val mutex = Mutex()

    override fun observeSettings(): Flow<SettingsSnapshot> = snapshotFlow.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.Default) {
        val identity = localIdentityProvider.current()
        val alias = readConfig(KEY_ALIAS) ?: identity.alias
        val policy = SyncPolicyId.fromWire(readConfig(KEY_POLICY))
        val cache = runCatching { storage.cacheSizeBytes() }.getOrDefault(0L)
        val downloadLocator = readConfig(KEY_DOWNLOAD_DIR)?.trim()?.takeIf { it.isNotEmpty() }

        snapshotFlow.value = SettingsSnapshot(
            alias = alias,
            deviceIdShort = identity.deviceId.value.take(12),
            fingerprintFormatted = formatFingerprint(identity.fingerprint.hex),
            syncPolicy = policy,
            cacheSizeBytes = cache,
            downloadDirLocator = downloadLocator,
            downloadDirDisplay = formatDownloadDirDisplay(downloadLocator),
            isDefaultDownloadDir = downloadLocator == null
        )
    }

    override suspend fun setDownloadDirectory(locator: String): Result<Unit> = mutex.withLock {
        val trimmed = locator.trim()
        if (trimmed.isBlank()) {
            return@withLock Result.failure(IllegalArgumentException("download directory must not be blank"))
        }
        withContext(Dispatchers.Default) {
            writeConfig(KEY_DOWNLOAD_DIR, trimmed)
        }
        refresh()
        Napier.i("settings: download directory updated to '$trimmed'")
        Result.success(Unit)
    }

    override suspend fun resetDownloadDirectory(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.Default) {
            deleteConfig(KEY_DOWNLOAD_DIR)
        }
        refresh()
        Napier.i("settings: download directory reset to default")
        Result.success(Unit)
    }

    override fun currentDownloadDirectory(): String? {
        val cached = snapshotFlow.value.downloadDirLocator
        if (cached != null) return cached
        return readConfig(KEY_DOWNLOAD_DIR)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun setAlias(alias: String): Result<Unit> = mutex.withLock {
        val trimmed = alias.trim().take(64)
        if (trimmed.isBlank()) {
            return@withLock Result.failure(IllegalArgumentException("alias must not be blank"))
        }
        withContext(Dispatchers.Default) {
            writeConfig(KEY_ALIAS, trimmed)
        }
        localIdentityProvider.updateAlias(trimmed)
        refresh()
        Napier.i("settings: alias updated to '$trimmed'")
        Result.success(Unit)
    }

    override suspend fun setSyncPolicy(id: SyncPolicyId): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.Default) {
            writeConfig(KEY_POLICY, id.wire)
        }
        refresh()
        Napier.i("settings: sync policy updated to ${id.wire}")
        Result.success(Unit)
    }

    override suspend fun clearCache(): Result<Long> = mutex.withLock {
        val before = runCatching { storage.cacheSizeBytes() }.getOrDefault(0L)
        // Triggers a sweep that respects the orphan-retention window - we
        // intentionally don't expose a "delete EVERYTHING" path because
        // active transfers might still own temp files.
        val report = runCatching { storage.sweep() }.getOrNull()
            ?: return@withLock Result.failure(RuntimeException("storage sweep failed"))
        refresh()
        val freed = report.bytesReclaimedFromCache
        Napier.i("settings: cache cleared, freed=${freed}B (was ${before}B)")
        Result.success(freed)
    }

    private fun readConfig(key: String): String? = try {
        database.configQueries.get(key).executeAsOneOrNull()
    } catch (t: Throwable) {
        Napier.w("config.read($key) failed: ${t.message}")
        null
    }

    private fun writeConfig(key: String, value: String) = try {
        database.configQueries.set(key, value)
    } catch (t: Throwable) {
        Napier.w("config.write($key) failed: ${t.message}")
    }

    private fun deleteConfig(key: String) = try {
        database.configQueries.delete(key)
    } catch (t: Throwable) {
        Napier.w("config.delete($key) failed: ${t.message}")
    }

    private fun formatDownloadDirDisplay(locator: String?): String {
        if (locator.isNullOrBlank()) return SettingsSnapshot.DEFAULT_DOWNLOAD_DIR_DISPLAY
        // Turn Android SAF tree URIs like
        // content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMyDir
        // into a clean readable path.
        val decoded = percentDecode(locator)
        val afterTree = if (decoded.contains("/tree/")) decoded.substringAfterLast("/tree/") else decoded
        val cleaned = afterTree
            .removePrefix("file://")
            .removePrefix("primary:")
            .trimStart('/')
        return cleaned.ifBlank { locator }
    }

    private fun percentDecode(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.append(hex.toChar())
                    i += 3
                    continue
                }
            }
            out.append(input[i])
            i += 1
        }
        return out.toString()
    }

    /**
     * Format a SHA-256 fingerprint for the Settings UI.
     *
     * Issue #46: the previous implementation displayed only the first 16
     * hex chars (= 64 bits), which is far below collision resistance for
     * a SHA-256 fingerprint. We now display the full 64 hex digits in
     * 8-char groups (e.g. "abcd1234 ef567890 ...") so the user can verify
     * the full fingerprint over the OOB channel.
     */
    private fun formatFingerprint(hex: String): String {
        val full = hex.lowercase()
        return full.chunked(8).joinToString(" ")
    }

    companion object {
        const val KEY_ALIAS: String = "device.alias"
        const val KEY_POLICY: String = "sync.policy"
        const val KEY_DOWNLOAD_DIR: String = "storage.download_dir"

        val EMPTY = SettingsSnapshot.EMPTY
    }
}

/** Convenience for [SyncMode] formatting in shared UI strings. */
fun SyncMode.Label(): String = when (this) {
    SyncMode.OneWayPush -> "Push only"
    SyncMode.OneWayPull -> "Pull only"
    SyncMode.TwoWay -> "Two-way sync"
}