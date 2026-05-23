package com.kfilesync.mobile.bootstrap

import com.kfilesync.mobile.di.bootstrap.bootstrap
import com.kfilesync.mobile.di.iosModule

/**
 * Swift-friendly entry point. Call from `iOSApp.init`:
 *
 * ```swift
 * @main
 * struct iOSApp: App {
 * init() {
 * IosBootstrapKt.iosBootstrap()
 * }
 * // ...
 * }
 * ```
 *
 * Swift can't easily pass Kotlin lists/modules across the bridge, so we
 * wrap the commonMain entry point with the iOS-specific module list baked
 * in. Calling this twice will throw (Koin's GlobalContext is single-shot);
 * the call site in `iOSApp.init` runs exactly once per process so that's fine.
 */
fun iosBootstrap() {
    bootstrap(platformModules = listOf(iosModule))
}