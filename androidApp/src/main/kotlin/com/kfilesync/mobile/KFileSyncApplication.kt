package com.kfilesync.mobile

import android.app.Application
import com.kfilesync.mobile.application.handler.CascadeHandler
import com.kfilesync.mobile.application.handler.SecurityHandler
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.DiscoveryCoordinator
import com.kfilesync.mobile.application.service.HeartbeatService
import com.kfilesync.mobile.di.androidModule
import com.kfilesync.mobile.di.bootstrap.bootstrap
import com.kfilesync.mobile.di.uiModule
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.infrastructure.network.HttpServer
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext

/**
 * Android `Application` subclass - initialises the Koin DI graph (sharedModule
 * + androidModule + uiModule) and starts the Phase 1 runtime services.
 * * Startup order matters for zero-trust (T1.4):
 * 1. Koin starts - platform module triggers DeviceIdentityProvider on first
 * access, which mints / loads the EC P-256 keypair from AndroidKeyStore.
 * 2. SecurityHandler + CascadeHandler subscribe to the bus.
 * 3. HttpServer starts on 0.0.0.0:53317 with sslConnector wired to the
 * AndroidKeyStore-backed JVM KeyStore (private key never leaves TEE).
 * Peers see TLS 1.3 with our self-signed cert.
 * 4. DiscoveryCoordinator advertises + listens via NsdManager.
 * 5. HeartbeatService starts the 30 s ping loop. Outbound HTTPS goes
 * through LanSyncHttpClient, which uses the pinned OkHttp engine
 * - fingerprint check against 'findPaired()' for every connection.
 * * All long-running services are scoped to the app's lifetime; Phase 5 will
 * push them into a foreground service so they survive process death during
 * transfers.
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

    override fun onCreate() {
        super.onCreate()
        bootstrap(
            platformModules = listOf(androidModule, uiModule),
            extraConfig = { androidContext(this@KFileSyncApplication) }
        )

        // ---- T1.5 wire event handlers ----
        securityHandler.register(eventBus)
        cascadeHandler.register(eventBus)

        // ---- T1.1 force-materialise the local identity ----
        // Touching localIdentityProvider.current() triggers
        // PlatformBackedLocalIdentityProvider -> AndroidDeviceIdentityProvider
        // -> generates/loads the KeyStore alias 'kfilesync:local'. Doing this
        // *before* httpServer.start() guarantees the alias exists when Ktor
        // CIO's sslConnector reaches into the AndroidKeyStore for the
        // private key.
        val identity = localIdentityProvider.current()
        Napier.i("local identity ready: deviceId=${identity.deviceId.value.take(12)}…")

        // ---- T1.4 + T1.2 + T1.9 start runtime services ----
        httpServer.start()
        appScope.launch {
            runCatching { discoveryCoordinator.start() }
                .onFailure { Napier.w("discoveryCoordinator.start failed", it) }
        }
        heartbeatService.start()

        Napier.i("KFileSyncApplication ready")
    }
}