package com.kfilesync.mobile.bootstrap

import com.kfilesync.mobile.application.handler.CascadeHandler
import com.kfilesync.mobile.application.handler.SecurityHandler
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.DiscoveryCoordinator
import com.kfilesync.mobile.application.service.HeartbeatService
import com.kfilesync.mobile.di.bootstrap.bootstrap
import com.kfilesync.mobile.di.iosModule
import com.kfilesync.mobile.di.uiModule
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.infrastructure.network.HttpServer
import com.kfilesync.mobile.platform.tls.IosTlsListener
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

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
 * 4. IosTlsListener starts on 0.0.0.0:53317 (TLS sidecar forwards to
 * the loopback engine). Order matters - the listener needs the
 * loopback to be up so its first forwarded connection succeeds.
 * 5. DiscoveryCoordinator starts (announces + listens via nw_ listener).
 * 6. HeartbeatService starts the 30s ping loop.
 *
 * Calling this twice would throw (Koin's GlobalContext is single-shot);
 * the iOS app initializer runs exactly once per process.
 */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun iosBootstrap() {
    bootstrap(platformModules = listOf(iosModule, uiModule))

    val koin = GlobalContext.get()
    val bus: EventBus = koin.get()

    // 1. Event handlers first (cheap, no IO).
    koin.get<SecurityHandler>().register(bus)
    koin.get<CascadeHandler>().register(bus)

    // 2. Force-materialise local identity - generates the Keychain EC
    //    keypair + mints the cert. IosTlsListener reads the cert PEM from
    //    LocalIdentity.current() so this must happen before step 4.
    val identity = koin.get<LocalIdentityProvider>().current()
    Napier.i("local identity ready: deviceId=${identity.deviceId.value.take(12)}...")

    // 3. Loopback HTTP server (plaintext on 127.0.0.1:53318).
    koin.get<HttpServer>().start()

    // 4. TLS-terminating sidecar (public on 0.0.0.0:53317).
    koin.get<IosTlsListener>().start()

    // 5. Discovery.
    val discovery: DiscoveryCoordinator = koin.get()
    appScope.launch {
        runCatching { discovery.start() }
            .onFailure { Napier.w("discoveryCoordinator.start failed", it) }
    }

    // 6. Heartbeat.
    koin.get<HeartbeatService>().start()

    Napier.i("iosBootstrap complete (TLS listener on :53317, loopback Ktor on :53318)")
}