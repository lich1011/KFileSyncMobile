package com.kfilesync.mobile.di

import android.os.Build
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.identity.PlatformBackedLocalIdentityProvider
import com.kfilesync.mobile.application.service.StorageMaintenanceAdapter
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.port.AppLifecycleMonitor
import com.kfilesync.mobile.domain.port.BatteryMonitor
import com.kfilesync.mobile.domain.port.DeviceIdentityProvider
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.DirectoryPicker
import com.kfilesync.mobile.domain.port.DirectoryScanner
import com.kfilesync.mobile.domain.port.DiscoveryProvider
import com.kfilesync.mobile.domain.port.FilePicker
import com.kfilesync.mobile.domain.port.FileSink
import com.kfilesync.mobile.domain.port.FileSource
import com.kfilesync.mobile.domain.port.FileWatcher
import com.kfilesync.mobile.domain.port.KeyStore
import com.kfilesync.mobile.domain.port.NetworkMonitor
import com.kfilesync.mobile.infrastructure.network.HttpServer
import com.kfilesync.mobile.infrastructure.network.HttpServerTlsConfig
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import com.kfilesync.mobile.infrastructure.network.pinnedHttpClientEngine
import com.kfilesync.mobile.infrastructure.persistence.DriverFactory
import com.kfilesync.mobile.platform.AndroidAppLifecycleMonitor
import com.kfilesync.mobile.platform.AndroidBatteryMonitor
import com.kfilesync.mobile.platform.AndroidDeviceIdentityProviderImpl
import com.kfilesync.mobile.platform.AndroidDirectoryPicker
import com.kfilesync.mobile.platform.AndroidDirectoryScanner
import com.kfilesync.mobile.platform.AndroidFilePicker
import com.kfilesync.mobile.platform.AndroidFileSink
import com.kfilesync.mobile.platform.AndroidFileSource
import com.kfilesync.mobile.platform.AndroidFileWatcher
import com.kfilesync.mobile.platform.AndroidKeyStoreAdapter
import com.kfilesync.mobile.platform.AndroidNetworkMonitor
import com.kfilesync.mobile.platform.AndroidNsdDiscovery
import com.kfilesync.mobile.platform.AndroidStorageMaintenanceAdapter
import com.kfilesync.mobile.platform.tls.PinningTrustManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Android-specific Koin module - binds the platform adapters that fulfil
 * the cross-platform Ports.
 *
 * Phase 1 wires identity / discovery / TLS pinning.
 * Phase 2 (T2.2 / T2.3) adds FilePicker / FileSource / FileSink.
 * Phase 3 (T3.2) adds DirectoryPicker.
 * Phase 4 (T4.1 / T4.2) adds DirectoryScanner + FileWatcher.
 * Phase 5 (T5.1 / T5.4 / T5.5) adds BatteryMonitor / NetworkMonitor /
 * AppLifecycleMonitor / StorageMaintenanceAdapter, and threads
 * `NonceWindow` into `HttpServer` (anti-replay, T5.3).
 */
val androidModule = module {

    // ---- SQLDelight driver ----
    single { DriverFactory(androidContext()) }

    // ---- KeyStore adapter ----
    single<KeyStore> { AndroidKeyStoreAdapter() }

    // ---- Device cryptographic identity ----
    single<DeviceIdentityProvider> { AndroidDeviceIdentityProviderImpl() }

    // ---- Local identity bridge ----
    single<LocalIdentityProvider> {
        PlatformBackedLocalIdentityProvider(
            identityProvider = get(),
            platform = DevicePlatform.Android,
            defaultAlias = "Android " + (Build.MODEL ?: "device"),
            port = 53317
        )
    }

    // ---- Discovery ----
    single<DiscoveryProvider> { AndroidNsdDiscovery(androidContext()) }

    // ---- TLS plumbing (T1.4) ----
    single { PinningTrustManager(pinned = get()) }
    single { HttpServerTlsConfig.load(get<PinningTrustManager>()) }

    // ---- File I/O (T2.2 / T2.3) ----
    single<AndroidFilePicker> { AndroidFilePicker() }
    single<FilePicker> { get<AndroidFilePicker>() }
    single<FileSource> { AndroidFileSource(androidContext()) }
    single<FileSink> { AndroidFileSink(androidContext()) }

    // ---- Directory picker (T3.2) ----
    single<AndroidDirectoryPicker> { AndroidDirectoryPicker() }
    single<DirectoryPicker> { get<AndroidDirectoryPicker>() }

    // ---- Directory scanner (T4.1) ----
    single<DirectoryScanner> { AndroidDirectoryScanner(androidContext()) }

    // ---- File watcher (T4.2) ----
    single<FileWatcher> { AndroidFileWatcher() }

    // ---- Phase 5 monitors ----
    single<BatteryMonitor> { AndroidBatteryMonitor(androidContext()) }
    single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
    single<AppLifecycleMonitor> { AndroidAppLifecycleMonitor() }
    single<StorageMaintenanceAdapter> {
        AndroidStorageMaintenanceAdapter(
            context = androidContext(),
            database = get()
        )
    }

    // ---- HTTP server (TLS on 0.0.0.0:53317) ----
    single {
        HttpServer(
            identityProvider = get(),
            pairingService = get(),
            transferService = get(),
            shareService = get(),
            fileIndexRepository = get(),
            shareRepository = get(),
            deviceRepository = get(),
            nonceWindow = get(),
            port = 53317,
            host = "0.0.0.0",
            tlsConfig = get<HttpServerTlsConfig>()
        )
    }

    // ---- HTTP client (pinned OkHttp engine) ----
    single {
        LanSyncHttpClient(
            engineFactory = pinnedHttpClientEngine(pinned = get())
        )
    }
}