package com.kfilesync.mobile.di

import com.kfilesync.mobile.ui.screens.devices.DevicesViewModel
import com.kfilesync.mobile.ui.screens.settings.SettingsViewModel
import com.kfilesync.mobile.ui.screens.shares.SharesViewModel
import com.kfilesync.mobile.ui.screens.sync.SyncViewModel
import com.kfilesync.mobile.ui.screens.transfer.TransfersViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * UI-layer Koin module (T1.7 / T1.8 / T2.6 / T3.6 / T5.3).
 *
 * Lives in sharedUI because Compose ViewModels reference UI-state types from
 * `sharedUI.ui.screens.*`, and sharedLogic deliberately doesn't depend on
 * sharedUI. The Android / iOS app shells should call:
 *
 * ---
 * bootstrap(platformModules = listOf(androidModule, uiModule))    // Android
 * bootstrap(platformModules = listOf(iosModule, uiModule))        // iOS (via iosBootstrap())
 * ---
 *
 * `viewModelOf` resolves dependencies via constructor injection - every
 * argument is bound in sharedModule or androidModule / iosModule, so Koin
 * can build the ViewModels automatically.
 */
val uiModule = module {
    viewModelOf(::DevicesViewModel)
    viewModelOf(::TransfersViewModel)
    viewModelOf(::SharesViewModel)
    viewModelOf(::SyncViewModel)
    viewModelOf(::SettingsViewModel)
}