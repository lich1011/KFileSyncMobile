package com.kfilesync.mobile.di

import com.kfilesync.mobile.application.identity.LocalIdentity
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.port.KeyStore
import com.kfilesync.mobile.infrastructure.persistence.DriverFactory
import com.kfilesync.mobile.platform.IosKeychainAdapter
import org.koin.dsl.module

/**
 * iOS-specific Koin module - binds the platform adapters that fulfil the
 * cross-platform Ports.
 *
 * Phase 0 brings up:
 * - DriverFactory (NativeSqliteDriver)
 * - LocalIdentity stub
 * - KeyStore adapter shape (methods throw until Phase 1 T1.1)
 */
val iosModule = module {

    // ---- SQLDelight driver ----
    single { DriverFactory() }

    // ---- Platform identity (Phase 0 stub) ----
    single<LocalIdentity> {
        LocalIdentity(
            deviceId = DeviceId(value = "phase0-ios-deviceId"),
            alias = "iOS device",
            platform = DevicePlatform.IOS,
            fingerprint = Fingerprint(hex = PHASE_0_PLACEHOLDER_FINGERPRINT),
            port = 53317
        )
    }

    // ---- KeyStore (shape only - methods throw until Phase 1 T1.1) ----
    single<KeyStore> { IosKeychainAdapter() }
}

private const val PHASE_0_PLACEHOLDER_FINGERPRINT =
    "0000000000000000000000000000000000000000000000000000000000000000"