package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.db.KFileSyncDatabase
import com.kfilesync.mobile.domain.service.SyncPolicy
import com.kfilesync.mobile.domain.service.SyncPolicyId
import com.kfilesync.mobile.domain.service.syncPolicyFor
import io.github.aakira.napier.Napier

/**
 * Reads the user-selected [SyncPolicy] from the `config` table at every
 * call (T5.1). Used by the Android `SyncWorker` and the iOS
 * `IosBackgroundSync` so that a settings change takes effect on the *next*
 * scheduled sync window without needing a full DI graph rebuild.
 *
 * The lookup is cheap (a single indexed SELECT under a synchronous
 * SQLDelight call), so we don't cache. If reading fails we fall back to
 * the [SyncPolicyId.Default] policy - sync continues with conservative
 * defaults rather than getting stuck.
 *
 * Lives in `application.service` because it touches the database directly;
 * the [SyncPolicy] interface itself stays pure in `domain.service`.
 */
class SyncPolicyProvider(
    private val database: KFileSyncDatabase
) {
    /** Resolve the current policy. Never throws - falls back to [SyncPolicyId.Default]. */
    fun current(): SyncPolicy = syncPolicyFor(currentId())

    /** Resolve just the id, useful for logging without instantiating the policy object. */
    fun currentId(): SyncPolicyId = try {
        val wire = database.configQueries.get(SettingsServiceImpl.KEY_POLICY).executeAsOneOrNull()
        SyncPolicyId.fromWire(wire)
    } catch (t: Throwable) {
        Napier.w("SyncPolicyProvider.currentId: ${t.message}; falling back to Default")
        SyncPolicyId.Default
    }
}