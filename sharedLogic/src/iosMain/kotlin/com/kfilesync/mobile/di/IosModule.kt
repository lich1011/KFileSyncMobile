package com.kfilesync.mobile.di

import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.identity.PlatformBackedLocalIdentityProvider
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.port.DeviceIdentityProvider
import com.kfilesync.mobile.domain.port.DiscoveryProvider
import com.kfilesync.mobile.domain.port.KeyStore
import com.kfilesync.mobile.infrastructure.network.HttpServer
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import com.kfilesync.mobile.infrastructure.network.pinnedHttpClientEngine
import com.kfilesync.mobile.infrastructure.persistence.DriverFactory
import com.kfilesync.mobile.platform.IosDeviceIdentityProviderImpl
import com.kfilesync.mobile.platform.IosKeychainAdapter
import com.kfilesync.mobile.platform.IosNWBrowserDiscovery
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
            platform = DevicePlatform.iOS,
            defaultAlias = "iPhone " + UIDevice.currentDevice.model,
            port = 53317
        )
    }

    // ---- Discovery ----
    single<DiscoveryProvider> { IosNWBrowserDiscovery() }

    // ---- HTTP server: plaintext loopback on 53318 ----
    // The public TLS endpoint is owned by IosTlsListener below.
    single {
        HttpServer(
            identityProvider = get(),
            pairingService = get(),
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
            engineFactory = pinnedHttpClientEngine(deviceRepository = get())
        )
    }
}