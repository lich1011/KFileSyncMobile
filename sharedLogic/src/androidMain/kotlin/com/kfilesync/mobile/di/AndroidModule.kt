package com.kfilesync.mobile.di

import android.os.Build
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.identity.PlatformBackedLocalIdentityProvider
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.port.DeviceIdentityProvider
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.DiscoveryProvider
import com.kfilesync.mobile.domain.port.KeyStore
import com.kfilesync.mobile.infrastructure.network.HttpServer
import com.kfilesync.mobile.infrastructure.network.HttpServerTlsConfig
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import com.kfilesync.mobile.infrastructure.network.pinnedHttpClientEngine
import com.kfilesync.mobile.infrastructure.persistence.DriverFactory
import com.kfilesync.mobile.platform.AndroidDeviceIdentityProviderImpl
import com.kfilesync.mobile.platform.AndroidKeyStoreAdapter
import com.kfilesync.mobile.platform.AndroidNsdDiscovery
import com.kfilesync.mobile.platform.tls.PinningTrustManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Android-specific Koin module - binds the platform adapters that fulfil
 * the cross-platform Ports.
 *
 * Phase 1 (T1.4 zero-trust on Android):
 * - DriverFactory (SQLite)
 * - KeyStore (AndroidKeyStoreAdapter)
 * - DeviceIdentityProvider (EC P-256 keypair + self-signed cert in TEE)
 * - LocalIdentityProvider (lazy bridge to identity provider)
 * - DiscoveryProvider (NsdManager)
 * - PinningTrustManager (T1.4 - peer cert pinning against findPaired())
 * - HttpServer with TLS via sslConnector (AndroidKeyStore-backed)
 * - LanSyncHttpClient with pinned OkHttp engine
 *
 * ViewModel bindings live in the sharedUI uiModule.
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
    single { PinningTrustManager(deviceRepository = get<DeviceRepository>()) }
    single { HttpServerTlsConfig.load(get<PinningTrustManager>()) }

    // ---- HTTP server (TLS on 0.0.0.0:53317) ----
    single {
        HttpServer(
            identityProvider = get(),
            pairingService = get(),
            port = 53317,
            host = "0.0.0.0",
            tlsConfig = get<HttpServerTlsConfig>()
        )
    }

    // ---- HTTP client (pinned OkHttp engine) ----
    single {
        LanSyncHttpClient(
            engineFactory = pinnedHttpClientEngine(deviceRepository = get())
        )
    }
}