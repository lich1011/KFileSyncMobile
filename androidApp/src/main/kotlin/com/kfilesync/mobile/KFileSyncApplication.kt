package com.kfilesync.mobile

import android.app.Application
import com.kfilesync.mobile.application.handler.CascadeHandler
import com.kfilesync.mobile.application.handler.SecurityHandler
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.CrashRecoveryService
import com.kfilesync.mobile.application.service.DiscoveryCoordinator
import com.kfilesync.mobile.application.service.HeartbeatService
import com.kfilesync.mobile.application.service.NetworkAwarenessService
import com.kfilesync.mobile.application.service.SettingsAppService
import com.kfilesync.mobile.application.service.ShareAppService
import com.kfilesync.mobile.application.service.SyncPolicyProvider
import com.kfilesync.mobile.application.service.TombstoneCleanupService
import com.kfilesync.mobile.application.service.TransferAppService
import com.kfilesync.mobile.domain.event.TransferRequested
import com.kfilesync.mobile.service.SyncForegroundService
import com.kfilesync.mobile.work.SyncWorker
import com.kfilesync.mobile.di.androidModule
import com.kfilesync.mobile.di.bootstrap.bootstrap
import com.kfilesync.mobile.di.uiModule
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.infrastructure.network.HttpServer
import com.kfilesync.mobile.infrastructure.network.PinnedTrustSnapshot
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext

/**
 * Android 'Application' subclass - initialises the Koin DI graph (sharedModule
 * + androidModule + uiModule) and starts the Phase 1 runtime services.
 *
 * Startup order matters for zero-trust (T1.4):
 * 1. Koin starts -> platform module triggers DeviceIdentityProvider on first
 * access, which mints / loads the EC P-256 keypair from AndroidKeyStore.
 * 2. SecurityHandler + CascadeHandler subscribe to the bus.
 * 3. HttpServer starts on 0.0.0.0:53317 with sslConnector wired to the
 * AndroidKeyStore-backed JVM KeyStore (private key never leaves TEE).
 * Peers see TLS 1.3 with our self-signed cert.
 * 4. DiscoveryCoordinator advertises + listens via NsdManager.
 * 5. HeartbeatService starts the 30 s ping loop. Outbound HTTPS goes
 * through LanSyncHttpClient, which uses the pinned OkHttp engine
 * - fingerprint check against `findPaired()` for every connection.
 *
 * Phase 5 additions:
 * - `CrashRecoveryService.recover()` runs as the very first post-DI step
 * so the rest of bootstrap sees a clean transfer + index baseline.
 * - `NetworkAwarenessService.start()` subscribes to NetworkMonitor and
 * auto-pauses / resumes transfers on Wi-Fi flap.
 * - `SettingsAppService.refresh()` materialises the settings snapshot
 * (alias, fingerprint, policy id, cache size) so the Settings screen
 * has data the instant the user taps the tab.
 * - SyncWorker is scheduled with the user's persisted policy's
 * `syncInterval()` instead of a hard-coded 15-minute period.
 */
class KFileSyncApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpServer: HttpServer by inject()
    private val discoveryCoordinator: DiscoveryCoordinator by inject()
    private val heartbeatService: HeartbeatService by inject()
    private val securityHandler: SecurityHandler by inject()
    private val cascadeHandler: CascadeHandler by inject()
    private val eventBus: EventBus by inject()
    private val localIdentityProvider: LocalIdentityProvider by inject()
    private val transferService: TransferAppService by inject()
    private val shareService: ShareAppService by inject()
    private val tombstoneCleanup: TombstoneCleanupService by inject()
    private val crashRecoveryService: CrashRecoveryService by inject()
    private val networkAwarenessService: NetworkAwarenessService by inject()
    private val settingsService: SettingsAppService by inject()
    private val syncPolicyProvider: SyncPolicyProvider by inject()
    private val pinnedTrustSnapshot: PinnedTrustSnapshot by inject()

    override fun onCreate() {
        super.onCreate()
        bootstrap(
            platformModules = listOf(androidModule, uiModule),
            extraConfig = { androidContext(this@KFileSyncApplication) }
        )

        // ---- T1.5 wire event handlers ----
        securityHandler.register(eventBus)
        cascadeHandler.register(eventBus)

        // ---- Hardened pinned-trust cache: bind to event bus so PairingCompleted
        //      / TrustRevoked auto-refresh the in-memory fingerprint set. The
        //      bind() call also kicks off the first refresh so the cache is
        //      warm before the HTTP server accepts its first connection.
        pinnedTrustSnapshot.bind(eventBus)

        // ---- T1.1 force-materialise the local identity ----
        // Touching localIdentityProvider.current() triggers
        // PlatformBackedLocalIdentityProvider -> AndroidDeviceIdentityProvider
        // -> generates/loads the KeyStore alias 'kfilesync:local'. Doing this
        // *before* httpServer.start() guarantees the alias exists when Ktor
        // CIO's sslConnector reaches into the AndroidKeyStore for the
        // private key.
        val identity = localIdentityProvider.current()
        Napier.i("local identity ready: deviceId=${identity.deviceId.value.take(12)}")

        // ---- T5.2 crash recovery - must run before transfer / share rehydration
        //      so the demoted-Verifying jobs surface in the same shape the UI
        //      expects.
        appScope.launch {
            runCatching { crashRecoveryService.recover() }
                .onFailure { Napier.w("crashRecoveryService.recover failed", it) }
        }

        // ---- T1.4 + T1.2 + T1.9 start runtime services ----
        httpServer.start()
        appScope.launch {
            runCatching { discoveryCoordinator.start() }
                .onFailure { Napier.w("discoveryCoordinator.start failed", it) }
        }
        heartbeatService.start()

        // ---- T5.4 network change awareness ----
        // Subscribes to NetworkMonitor; auto-pauses active outgoing transfers
        // when Wi-Fi drops and resumes them on reconnect. Idempotent; safe to
        // call before any transfers exist.
        runCatching { networkAwarenessService.start() }
            .onFailure { Napier.w("networkAwarenessService.start failed", it) }

        // ---- T2.4 rehydrate transfer state after process restart ----
        // findIncompleteJobs() returns Pending/Active/Paused jobs; resumeAfterRestart
        // surfaces them via the TransferAppService flows so the UI lights up the
        // "Active" section immediately, and so a re-POST /transfer/request from
        // the peer can apply the resume map.
        appScope.launch {
            runCatching { transferService.resumeAfterRestart() }
                .onFailure { Napier.w("transferService.resumeAfterRestart failed", it) }
        }

        // ---- T3.1 rehydrate share state after process restart ----
        appScope.launch {
            runCatching { shareService.resumeAfterRestart() }
                .onFailure { Napier.w("shareService.resumeAfterRestart failed", it) }
        }

        // ---- T4.6 sweep stale tombstones at startup ----
        // Cheap and gives the indexer a clean baseline. Long-form daily sweeps
        // also happen inside SyncWorker.doWork() so a long-suspended app catches
        // up without depending on this one-shot call.
        appScope.launch {
            runCatching { tombstoneCleanup.sweepNow() }
                .onFailure { Napier.w("tombstoneCleanup.sweepNow failed", it) }
        }

        // ---- T5.3 prime the Settings snapshot ----
        // Cheap (single SELECT + a few file stats); needed so the Settings tab
        // shows the user's alias + fingerprint + cache stats the first time
        // they tap it, without a noticeable empty-state flash.
        appScope.launch {
            runCatching { settingsService.refresh() }
                .onFailure { Napier.w("settingsService.refresh failed", it) }
        }

        // ---- T4.7 + T5.1 schedule periodic sync (WorkManager, Wi-Fi only) ----
        // Idempotent; ExistingPeriodicWorkPolicy.UPDATE means a settings-driven
        // interval change replaces the existing schedule cleanly. The interval
        // comes from the user's persisted SyncPolicy.
        runCatching {
            val policy = syncPolicyProvider.current()
            val intervalMinutes = policy.syncInterval().inWholeMinutes
                .coerceAtLeast(15L) // WorkManager periodic floor
            SyncWorker.schedule(this, intervalMinutes = intervalMinutes)
            Napier.i("SyncWorker scheduled per policy=${policy.id.wire} interval=${intervalMinutes}m")
        }.onFailure { Napier.w("SyncWorker.schedule failed", it) }

        // ---- T2.5 start the foreground service on the first transfer event ----
        // We don't keep the service alive when idle (battery), so subscribe
        // to TransferRequested events and start the service the first time we
        // see one. The service itself decides when to stop (no active rows).
        appScope.launch {
            eventBus.events()
                .collect { ev ->
                    if (ev is TransferRequested) {
                        SyncForegroundService.start(this@KFileSyncApplication)
                    }
                }
        }

        Napier.i("KFileSyncApplication ready")
    }
}