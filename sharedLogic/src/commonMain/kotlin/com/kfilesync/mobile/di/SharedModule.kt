package com.kfilesync.mobile.di

import com.kfilesync.mobile.application.handler.CascadeHandler
import com.kfilesync.mobile.application.handler.SecurityHandler
import com.kfilesync.mobile.application.service.DeviceAppService
import com.kfilesync.mobile.application.service.DeviceServiceImpl
import com.kfilesync.mobile.application.service.DiscoveryCoordinator
import com.kfilesync.mobile.application.service.HeartbeatService
import com.kfilesync.mobile.application.service.ManualIpProbe
import com.kfilesync.mobile.application.service.PairingService
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.PairingRequestRepository
import com.kfilesync.mobile.domain.service.ChunkingStrategy
import com.kfilesync.mobile.domain.service.ConflictResolver
import com.kfilesync.mobile.domain.service.PolicyEnforcer
import com.kfilesync.mobile.domain.service.SizeBasedChunking
import com.kfilesync.mobile.domain.service.SyncPlanGenerator
import com.kfilesync.mobile.infrastructure.events.SharedFlowEventBus
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightDeviceRepo
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightPairingRequestRepo
import com.kfilesync.mobile.infrastructure.persistence.createDatabase
import org.koin.dsl.module

/**
 * Cross-platform Koin module (design doc §12).
 *
 * Holds bindings identical on every platform. Platform-specific bindings
 * (DriverFactory, KeyStore adapter, DiscoveryProvider, DeviceIdentityProvider,
 * LocalIdentityProvider, FileWatcher, **HttpServer**, **LanSyncHttpClient**)
 * live in 'AndroidModule' / 'IosModule' because they need platform-specific
 * TLS configuration (T1.4).
 */
val sharedModule = module {

    // ---- Persistence ----
    single { createDatabase(get()) }
    single<DeviceRepository> { SqlDelightDeviceRepo(get()) }
    single<PairingRequestRepository> { SqlDelightPairingRequestRepo(get()) }

    // ---- Cross-cutting infra ----
    single<EventBus> { SharedFlowEventBus() }

    // ---- Domain services (pure) ----
    factory { ConflictResolver() }
    factory { SyncPlanGenerator() }
    factory { PolicyEnforcer(get(), get()) }
    factory<ChunkingStrategy> { SizeBasedChunking() }

    // ---- Application handlers ----
    single { SecurityHandler(deviceRepository = get(), keyStore = get()) }
    single { CascadeHandler() }

    // ---- Application services ----
    single { PairingService(get(), get(), get(), get(), get()) }
    single { DiscoveryCoordinator(provider = get(), localIdentityProvider = get()) }
    single { ManualIpProbe(fetchInfo =get()) }
    single { HeartbeatService(deviceRepository = get(), httpClient = get()) }
    single<DeviceAppService> {
        DeviceServiceImpl(
            deviceRepository = get(),
            pairingService = get(),
            discoveryCoordinator = get(),
            eventBus = get()
        )
    }

    // ---- Networking, identity ----
    // LocalIdentityProvider, HttpServer, LanSyncHttpClient: all provided by
    // the platform module (they each need TLS / device-identity wiring that
    // varies between Android sslConnector and iOS nw_listener).
}