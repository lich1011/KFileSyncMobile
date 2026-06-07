package com.kfilesync.mobile.di

import com.kfilesync.mobile.application.handler.CascadeHandler
import com.kfilesync.mobile.application.handler.SecurityHandler
import com.kfilesync.mobile.application.service.CrashRecoveryService
import com.kfilesync.mobile.application.service.DeviceAppService
import com.kfilesync.mobile.application.service.DeviceServiceImpl
import com.kfilesync.mobile.application.service.DiscoveryCoordinator
import com.kfilesync.mobile.application.service.HeartbeatService
import com.kfilesync.mobile.application.service.ManualIpProbe
import com.kfilesync.mobile.application.service.NetworkAwarenessService
import com.kfilesync.mobile.application.service.PairingService
import com.kfilesync.mobile.application.service.SettingsAppService
import com.kfilesync.mobile.application.service.SettingsServiceImpl
import com.kfilesync.mobile.application.service.ShareAppService
import com.kfilesync.mobile.application.service.ShareServiceImpl
import com.kfilesync.mobile.application.service.StorageMaintenanceService
import com.kfilesync.mobile.application.service.SyncAppService
import com.kfilesync.mobile.application.service.SyncIgnoreReader
import com.kfilesync.mobile.application.service.SyncPolicyProvider
import com.kfilesync.mobile.application.service.SyncServiceImpl
import com.kfilesync.mobile.application.service.TombstoneCleanupService
import com.kfilesync.mobile.application.service.TransferAppService
import com.kfilesync.mobile.application.service.TransferServiceImpl
import com.kfilesync.mobile.domain.model.Share
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.FileIndexRepository
import com.kfilesync.mobile.domain.port.PairingRequestRepository
import com.kfilesync.mobile.domain.port.ShareRepository
import com.kfilesync.mobile.domain.port.TransferRepository
import com.kfilesync.mobile.domain.port.TrustBootstrapState
import com.kfilesync.mobile.domain.service.ChunkingStrategy
import com.kfilesync.mobile.domain.service.ConflictResolver
import com.kfilesync.mobile.domain.service.DefaultSyncPolicy
import com.kfilesync.mobile.domain.service.IgnoreSpec
import com.kfilesync.mobile.domain.service.Indexer
import com.kfilesync.mobile.domain.port.HashPort
import com.kfilesync.mobile.infrastructure.crypto.DefaultHashPort
import com.kfilesync.mobile.domain.service.NonceWindow
import com.kfilesync.mobile.domain.service.PolicyEnforcer
import com.kfilesync.mobile.domain.service.SizeBasedChunking
import com.kfilesync.mobile.domain.service.SyncPlanGenerator
import com.kfilesync.mobile.domain.service.SyncPolicy
import com.kfilesync.mobile.infrastructure.events.SharedFlowEventBus
import com.kfilesync.mobile.infrastructure.network.PinnedTrustSnapshot
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightDeviceRepo
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightFileIndexRepo
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightPairingRequestRepo
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightShareRepo
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightTransferRepo
import com.kfilesync.mobile.infrastructure.persistence.SqlDelightTrustBootstrapState
import com.kfilesync.mobile.infrastructure.persistence.createDatabase
import org.koin.dsl.module

/**
 * Cross-platform Koin module (design doc §12).
 *
 * Holds bindings identical on every platform. Platform-specific bindings
 * (DriverFactory, KeyStore adapter, DiscoveryProvider, DeviceIdentityProvider,
 * LocalIdentityProvider, FileWatcher, DirectoryScanner, HttpServer,
 * LanSyncHttpClient, FilePicker / FileSource / FileSink, DirectoryPicker,
 * BatteryMonitor, NetworkMonitor, AppLifecycleMonitor, StorageMaintenanceAdapter)
 * live in 'AndroidModule' / 'iOSModule'.
 *
 * Phase 5 adds:
 * - 'SyncPolicy' (default; user picks via Settings tab)
 * - 'SyncPolicyProvider' (reads persisted policy id from 'config')
 * - 'NonceWindow' (anti-replay)
 * - 'CrashRecoveryService' (boot-time recovery)
 * - 'NetworkAwarenessService' (auto-pause/resume)
 * - 'StorageMaintenanceService' (cache + temp cleanup)
 * - 'SettingsAppService' (alias + policy + cache stats)
 * - 'SyncIgnoreReader' (read share-root '.syncignore')
 * - 'Indexer' factory now uses 'SyncIgnoreReader' to compose per-share
 * ignore rules instead of 'withMobileDefaults()' only.
 */
val sharedModule = module {

    // ---- Persistence ----
    single { createDatabase(get()) }
    single<DeviceRepository> { SqlDelightDeviceRepo(get()) }
    single<PairingRequestRepository> { SqlDelightPairingRequestRepo(get()) }
    single<TransferRepository> { SqlDelightTransferRepo(get()) }
    single<ShareRepository> { SqlDelightShareRepo(get()) }
    single<FileIndexRepository> { SqlDelightFileIndexRepo(get()) }
    single<TrustBootstrapState> { SqlDelightTrustBootstrapState(get()) }

    // ---- TLS pinning snapshot (refactored from per-handshake DB hit) ----
    single { PinnedTrustSnapshot(deviceRepository = get(), trustBootstrapState = get()) }

    // ---- Cross-cutting infra ----
    single<EventBus> { SharedFlowEventBus() }

    // ---- Domain services (pure) ----
    factory { ConflictResolver() }
    factory { SyncPlanGenerator() }
    factory { PolicyEnforcer(get(), get()) }
    factory<ChunkingStrategy> { SizeBasedChunking() }
    single<SyncPolicy> { DefaultSyncPolicy() }
    single<HashPort> { DefaultHashPort() }
    single { NonceWindow() }

    // ---- Application handlers ----
    single { SecurityHandler(deviceRepository = get(), keyStore = get(), trustBootstrapState = get(), transferService = get()) }
    single {
        CascadeHandler(
            shareRepository = get(),
            deviceRepository = get(),
            httpClient = get(),
            localIdentityProvider = get(),
            eventBus = get()
        )
    }

    // ---- Application services ----
    single { PairingService(get(), get(), get(), get(), get()) }
    single { DiscoveryCoordinator(provider = get(), localIdentityProvider = get()) }
    single { ManualIpProbe(httpClient = get<com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient>()) }
    single { HeartbeatService(deviceRepository = get(), httpClient = get()) }
    single<DeviceAppService> {
        DeviceServiceImpl(
            deviceRepository = get(),
            pairingService = get(),
            discoveryCoordinator = get(),
            eventBus = get()
        )
    }
    single<TransferAppService> {
        TransferServiceImpl(
            transferRepository = get(),
            deviceRepository = get(),
            eventBus = get(),
            httpClient = get(),
            localIdentityProvider = get(),
            fileSource = get(),
            fileSink = get(),
            chunkingStrategy = get()
        )
    }
    single<ShareAppService> {
        ShareServiceImpl(
            shareRepository = get(),
            deviceRepository = get(),
            eventBus = get(),
            localIdentityProvider = get()
        )
    }
    single { TombstoneCleanupService(shareRepository = get(), fileIndexRepository = get()) }
    single { SyncIgnoreReader(directoryScanner = get(), fileSource = get()) }
    single<SyncAppService> {
        SyncServiceImpl(
            shareRepository = get(),
            deviceRepository = get(),
            fileIndexRepository = get(),
            policyEnforcer = get(),
            indexerFactory = { share: Share ->
                // Phase 5: per-share ignore spec - read .syncignore from share root.
                // The closure runs once per syncShare() call, so we block briefly
                // on the IO read; the result is cheap and not on a hot path.
                val reader = get<SyncIgnoreReader>()
                val ignoreSpec =  reader.loadForShare(share.localPath)
                Indexer(
                    fileIndexRepository = get(),
                    directoryScanner = get(),
                    fileSource = get(),
                    ignore = ignoreSpec,
                    hashPort = get()
                )
            },
            syncPlanGenerator = get(),
            conflictResolver = get(),
            transferService = get(),
            httpClient = get(),
            directoryScanner = get(),
            localIdentityProvider = get(),
            eventBus = get()
        )
    }

    // ---- Phase 5 services ----
    single { SyncPolicyProvider(database = get()) }
    single { StorageMaintenanceService(adapter = get(), transferRepository = get()) }
    single {
        CrashRecoveryService(
            transferRepository = get(),
            shareRepository = get(),
            fileIndexRepository = get(),
            directoryScanner = get(),
            tombstoneCleanup = get()
        )
    }
    single {
        NetworkAwarenessService(
            networkMonitor = get(),
            transferRepository = get()
        )
    }
    single<SettingsAppService> {
        SettingsServiceImpl(
            database = get(),
            storage = get(),
            localIdentityProvider = get()
        )
    }

    // ---- Networking, identity ----
    // LocalIdentityProvider, HttpServer, LanSyncHttpClient, DirectoryScanner,
    // BatteryMonitor, NetworkMonitor, AppLifecycleMonitor,
    // StorageMaintenanceAdapter;
    // all provided by the platform module (they each need TLS / device-identity
    // wiring or platform OS APIs that vary between Android SAF and iOS file
    // URLs).
}