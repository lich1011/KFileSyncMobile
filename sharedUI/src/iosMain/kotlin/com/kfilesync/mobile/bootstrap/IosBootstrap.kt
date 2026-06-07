package com.kfilesync.mobile.bootstrap

import com.kfilesync.mobile.application.handler.CascadeHandler
import com.kfilesync.mobile.application.handler.SecurityHandler
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.CrashRecoveryService
import com.kfilesync.mobile.application.service.DiscoveryCoordinator
import com.kfilesync.mobile.application.service.HeartbeatService
import com.kfilesync.mobile.application.service.NetworkAwarenessService
import com.kfilesync.mobile.application.service.SettingsAppService
import com.kfilesync.mobile.application.service.ShareAppService
import com.kfilesync.mobile.application.service.TombstoneCleanupService
import com.kfilesync.mobile.application.service.TransferAppService
import com.kfilesync.mobile.di.bootstrap.bootstrap
import com.kfilesync.mobile.di.iosModule
import com.kfilesync.mobile.di.uiModule
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.infrastructure.network.HttpServer
import com.kfilesync.mobile.infrastructure.network.PinnedTrustSnapshot
import com.kfilesync.mobile.platform.IosBackgroundSync
import com.kfilesync.mobile.platform.tls.IosTlsListener
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

/**
 * Swift-friendly entry point. Call from `iOSApp.init`:
 *
 * ```swift
 * @main
 * struct iOSApp: App {
 * init() {
 * IosBootstrapKt.iosBootstrap()
 * }
 * }
 * ```
 *
 * Phase 1 startup order (matters):
 * 1. Koin starts and resolves DI graph.
 * 2. SecurityHandler / CascadeHandler subscribe to the event bus before
 * anything that publishes runs.
 * 3. HttpServer starts on 127.0.0.1:53318 (loopback plaintext).
 * 4. IosTlsListener starts on 0.0.0.0:53317 (TLS, sidecar forwards to
 * the loopback engine). Order matters - the listener needs the
 * loopback to be up so its first forwarded connection succeeds.
 * 5. DiscoveryCoordinator starts (announces + listens via nw_listener).
 * 6. HeartbeatService starts the 30s ping loop.
 *
 * Phase 5 additions (carefully ordered):
 * - **BGTaskScheduler.register** is called FIRST, before any other work.
 * Apple's documentation is explicit: every identifier in the
 * `BGTaskSchedulerPermittedIdentifiers` Info.plist key must have a
 * handler registered *before* `application(_:didFinishLaunchingWithOptions:)`
 * returns; the system inspects the registered set at app launch and
 * refuses unregistered identifiers for the remainder of the process.
 * Calling [iosBootstrap] from `iOSApp.init` (which runs before the
 * SwiftUI scene first appears) satisfies that requirement.
 * - CrashRecoveryService runs as an early step so the Verifying->Active
 * demotions and stale-Pending cleanup happen before the UI rehydrates.
 * - NetworkAwarenessService subscribes to NWPathMonitor and auto-
 * pauses / resumes transfers on Wi-Fi flap.
 * - SettingsAppService.refresh() materialises the snapshot (alias,
 * fingerprint, policy id, cache size).
 * - IosBackgroundSync.scheduleNext() queues the first BGAppRefreshTask.
 *
 * Calling this twice would throw (Koin's GlobalContext is single-shot);
 * the iOS app initialiser runs exactly once per process.
 */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun iosBootstrap() {
    bootstrap(platformModules = listOf(iosModule, uiModule))

    val koin = KoinPlatformTools.defaultContext().get()
    val bus: EventBus = koin.get()

    // 0. Background-task registration - MUST happen before iOSApp.init returns.
    //    Apple's BGTaskScheduler validates the registered identifier set at
    //    app launch; any identifier listed in BGTaskSchedulerPermittedIdentifiers
    //    that wasn't registered by then is rejected for the process lifetime.
    val backgroundSync: IosBackgroundSync = koin.get()
    runCatching { backgroundSync.registerTasks() }
        .onFailure { Napier.w("backgroundSync.registerTasks failed", it) }

    // 1. Event handlers (cheap, no IO).
    koin.get<SecurityHandler>().register(bus)
    koin.get<CascadeHandler>().register(bus)

    // 1a. Pinned-trust cache: bind to the event bus so PairingCompleted /
    //     TrustRevoked auto-refresh the in-memory fingerprint set. Must run
    //     before the HTTP server starts accepting connections (step 4) so
    //     the snapshot is warm by the time the first handshake arrives.
    koin.get<PinnedTrustSnapshot>().bind(bus)

    // 2. Force-materialise local identity - generates the Keychain EC
    //    keypair + mints the cert. IosTlsListener reads the cert PEM from
    //    LocalIdentity.current() so this must happen before step 4.
    val identity = koin.get<LocalIdentityProvider>().current()
    Napier.i("local identity ready: deviceId=${identity.deviceId.value.take(12)}...")

    // 3. T5.2 crash recovery - before transfer / share rehydration.
    val crashRecoveryService: CrashRecoveryService = koin.get()
    appScope.launch {
        runCatching { crashRecoveryService.recover() }
            .onFailure { Napier.w("crashRecoveryService.recover failed", it) }
    }

    // 4. Loopback HTTP server (plaintext on 127.0.0.1:53318).
    koin.get<HttpServer>().start()

    // 5. TLS-terminating sidecar (public on 0.0.0.0:53317).
    koin.get<IosTlsListener>().start()

    // 6. Discovery.
    val discovery: DiscoveryCoordinator = koin.get()
    appScope.launch {
        runCatching { discovery.start() }
            .onFailure { Napier.w("discoveryCoordinator.start failed", it) }
    }

    // 7. Heartbeat.
    koin.get<HeartbeatService>().start()

    // 8. T5.4 network change awareness.
    val networkAwarenessService: NetworkAwarenessService = koin.get()
    runCatching { networkAwarenessService.start() }
        .onFailure { Napier.w("networkAwarenessService.start failed", it) }

    // 9. Rehydrate transfer state after restart (T2.4) - surfaces incomplete
    //    jobs in the TransferAppService flows so the UI shows them immediately
    //    and so a re-POST /transfer/request from a peer can apply the resume map.
    val transferService: TransferAppService = koin.get()
    appScope.launch {
        runCatching { transferService.resumeAfterRestart() }
            .onFailure { Napier.w("transferService.resumeAfterRestart failed", it) }
    }

    // 10. Rehydrate share state (T3.1) - publishes any persisted Pending /
    //     Active / Paused / Left shares into the ShareAppService rows flow so
    //     the UI lights up immediately on cold start.
    val shareService: ShareAppService = koin.get()
    appScope.launch {
        runCatching { shareService.resumeAfterRestart() }
            .onFailure { Napier.w("shareService.resumeAfterRestart failed", it) }
    }

    // 11. Tombstone cleanup (T4.6) - best-effort one-shot sweep at startup.
    //     Periodic sweeps land via BGProcessingTask (registered in step 0
    //     and queued in step 13) now that Phase 5 is wired in.
    val tombstones: TombstoneCleanupService = koin.get()
    appScope.launch {
        runCatching { tombstones.sweepNow() }
            .onFailure { Napier.w("tombstoneCleanup.sweepNow failed", it) }
    }

    // 12. T5.3 prime the Settings snapshot so the Settings tab renders
    //     instantly on first tap.
    val settingsService: SettingsAppService = koin.get()
    appScope.launch {
        runCatching { settingsService.refresh() }
            .onFailure { Napier.w("settingsService.refresh failed", it) }
    }

    // 13. T5.1 queue the first BG refresh + processing window.
    runCatching { backgroundSync.scheduleNext() }
        .onFailure { Napier.w("backgroundSync.scheduleNext failed", it) }

    Napier.i("iosBootstrap complete (TLS listener on :53317, loopback Ktor on :53318)")
}