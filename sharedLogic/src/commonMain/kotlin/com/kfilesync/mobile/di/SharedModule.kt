package com.kfilesync.mobile.di

import com.kfilesync.mobile.application.handler.CascadeHandler
import com.kfilesync.mobile.application.handler.SecurityHandler
import com.kfilesync.mobile.application.identity.InMemoryLocalIdentityProvider
import com.kfilesync.mobile.application.identity.LocalIdentity
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.DeviceAppService
import com.kfilesync.mobile.application.service.DeviceServiceImpl
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.service.ChunkingStrategy
import com.kfilesync.mobile.domain.service.ConflictResolver
import com.kfilesync.mobile.domain.service.PolicyEnforcer
import com.kfilesync.mobile.domain.service.SizeBasedChunking
import com.kfilesync.mobile.domain.service.SyncPlanGenerator
import com.kfilesync.mobile.infrastructure.events.SharedFlowEventBus
import com.kfilesync.mobile.infrastructure.network.HttpServer
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightDeviceRepo
import com.kfilesync.mobile.infrastructure.persistence.createDatabase
import org.koin.dsl.module

/**
 * Cross-platform Koin module (design doc §12).
 *
 * Holds bindings that are identical on every platform. Platform-specific
 * bindings (DriverFactory, KeyStore adapter, DiscoveryProvider, FileWatcher)
 * live in `AndroidModule` / `IosModule`.
 *
 * Note: the [LocalIdentity] singleton is created per-platform too (since it
 * needs the device's platform enum baked in) – the platform module provides
 * the LocalIdentity instance and this module binds the provider on top.
 */
val sharedModule = module {

    // ---- Persistence ----
    single { createDatabase(get()) }
    single<DeviceRepository> { SqlDelightDeviceRepo(get()) }

    // ---- Cross-cutting infra ----
    single<EventBus> { SharedFlowEventBus() }

    // ---- Domain services (pure) ----
    factory { ConflictResolver() }
    factory { SyncPlanGenerator() }
    factory { PolicyEnforcer(get(), get()) }
    factory<ChunkingStrategy> { SizeBasedChunking() }

    // ---- Application handlers ----
    factory { SecurityHandler() }
    factory { CascadeHandler() }

    // ---- Application services ----
    single<DeviceAppService> { DeviceServiceImpl(get(), get()) }

    // ---- Identity & networking ----
    single<LocalIdentityProvider> { InMemoryLocalIdentityProvider(get()) }
    single { HttpServer(identityProvider = get()) }
    single { LanSyncHttpClient() }
}