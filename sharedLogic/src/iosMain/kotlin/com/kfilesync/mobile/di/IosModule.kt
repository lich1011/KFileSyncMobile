package com.kfilesync.mobile.di

import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.identity.PlatformBackedLocalIdentityProvider
import com.kfilesync.mobile.application.service.StorageMaintenanceAdapter
import com.kfilesync.mobile.application.service.SyncPolicyProvider
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.port.AppLifecycleMonitor
import com.kfilesync.mobile.domain.port.BatteryMonitor
import com.kfilesync.mobile.domain.port.DeviceIdentityProvider
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
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import com.kfilesync.mobile.infrastructure.network.pinnedHttpClientEngine
import com.kfilesync.mobile.infrastructure.persistence.DriverFactory
import com.kfilesync.mobile.platform.IosAppLifecycleMonitor
import com.kfilesync.mobile.platform.IosBackgroundSync
import com.kfilesync.mobile.platform.IosBatteryMonitor
import com.kfilesync.mobile.platform.IosDeviceIdentityProviderImpl
import com.kfilesync.mobile.platform.IosDirectoryPicker
import com.kfilesync.mobile.platform.IosDirectoryScanner
import com.kfilesync.mobile.platform.IosFilePicker
import com.kfilesync.mobile.platform.IosFileSink
import com.kfilesync.mobile.platform.IosFileSource
import com.kfilesync.mobile.platform.IosFileWatcher
import com.kfilesync.mobile.platform.IosKeychainAdapter
import com.kfilesync.mobile.platform.IosNWBrowserDiscovery
import com.kfilesync.mobile.platform.IosNetworkMonitor
import com.kfilesync.mobile.platform.IosStorageMaintenanceAdapter
import com.kfilesync.mobile.platform.tls.IosTlsListener
import org.koin.dsl.module
import platform.UIKit.UIDevice

/**
 * iOS-specific Koin module - binds platform adapters fulfilling the
 * cross-platform Ports.
 *
 * Phase 1 (T1.4 zero-trust on iOS):
 * - DriverFactory (NativeSqliteDriver)
 * - KeyStore (IosKeychainAdapter - delete only)
 * - DeviceIdentityProvider (Security.framework EC P-256 + ASN.1 cert)
 * - LocalIdentityProvider (lazy bridge)
 * - DiscoveryProvider (nw_browser / nw_listener)
 * - HttpServer bound to 127.0.0.1:53318 plaintext loopback
 * - IosTlsListener bound to 0.0.0.0:53317 with Keychain SecIdentity,
 * forwarding decrypted bytes to the loopback engine
 * - LanSyncHttpClient with pinned Darwin engine (URLSessionDelegate
 * fingerprint pinning)
 *
 * The two-process pattern (TLS listener + plaintext Ktor) keeps the
 * private key Secure-Enclave-resident - the listener signs handshakes by
 * asking the OS via `sec_protocol_options_set_local_identity`; nothing
 * traverses user space as bytes.
 *
 * Phase 5 additions:
 * - BatteryMonitor (UIDevice + NSNotificationCenter)
 * - NetworkMonitor (nw_path_monitor)
 * - AppLifecycleMonitor (UIApplication notifications)
 * - StorageMaintenanceAdapter (NSTemporaryDirectory cleanup)
 * - IosBackgroundSync (BGTaskScheduler integration; registerTasks()
 * must be called by IosBootstrap before the first SwiftUI scene
 * appears - Apple validates registered identifiers at launch).
 */
val iosModule = module {

    // ---- SQLDelight driver ----
    single { DriverFactory() }

    // ---- KeyStore adapter ----
    single<KeyStore> { IosKeychainAdapter() }

    // ---- Device cryptographic identity ----
    single<DeviceIdentityProvider> { IosDeviceIdentityProviderImpl() }

    // ---- Local identity bridge ----
    single<LocalIdentityProvider> {
        PlatformBackedLocalIdentityProvider(
            identityProvider = get(),
            platform = DevicePlatform.IOS,
            defaultAlias = "iPhone " + UIDevice.currentDevice.model,
            port = 53317
        )
    }

    // ---- Discovery ----
    single<DiscoveryProvider> { IosNWBrowserDiscovery() }

    // ---- File I/O (T2.2 / T2.3) ----
    single<IosFilePicker> { IosFilePicker() }
    single<FilePicker> { get<IosFilePicker>() }
    single<FileSource> { IosFileSource() }
    single<FileSink> { IosFileSink() }

    // ---- Directory picker (T3.2) ----
    single<IosDirectoryPicker> { IosDirectoryPicker() }
    single<DirectoryPicker> { get<IosDirectoryPicker>() }

    // ---- Directory scanner (T4.1) ----
    single<DirectoryScanner> { IosDirectoryScanner() }

    // ---- File watcher (T4.2) ----
    single<FileWatcher> { IosFileWatcher() }

    // ---- HTTP server: plaintext loopback on 53318 ----
    // The public TLS endpoint is owned by IosTlsListener below.
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
            port = HttpServer.IOS_LOOPBACK_PORT,
            host = "127.0.0.1",
            tlsConfig = null // iOS: TLS is in the listener sidecar.
        )
    }

    // ---- iOS TLS terminator (T1.4) ----
    single {
        IosTlsListener(
            deviceRepository = get(),
            identityProvider = get(),
            publicPort = 53317,
            loopbackPort = HttpServer.IOS_LOOPBACK_PORT
        )
    }

    // ---- HTTP client (pinned Darwin engine) ----
    single {
        LanSyncHttpClient(
            engineFactory = pinnedHttpClientEngine(pinned = get())
        )
    }

    // ---- Phase 5 platform monitors ----
    single<BatteryMonitor> { IosBatteryMonitor() }
    single<NetworkMonitor> { IosNetworkMonitor() }
    single<AppLifecycleMonitor> { IosAppLifecycleMonitor() }
    single<StorageMaintenanceAdapter> { IosStorageMaintenanceAdapter(database = get()) }

    // ---- Phase 5 BGTaskScheduler integration (T5.1 on iOS) ----
    // The 'syncPolicy' lambda resolves the *user-selected* policy from the
    // 'config' table on every BGTask invocation, so changing the policy via
    // Settings takes effect on the next refresh window without rebooting
    // the DI graph.
    single {
        IosBackgroundSync(
            syncService = get(),
            shareService = get(),
            tombstones = get(),
            storage = get(),
            syncPolicy = { get<SyncPolicyProvider>().current() },
            networkMonitor = get(),
            batteryMonitor = get()
        )
    }
}