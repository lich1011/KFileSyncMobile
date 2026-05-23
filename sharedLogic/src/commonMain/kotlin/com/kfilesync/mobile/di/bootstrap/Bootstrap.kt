package com.kfilesync.mobile.di.bootstrap

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

/**
 * Single entry point for application bootstrap.
 *
 * Called from:
 * - `KFileSyncApplication.onCreate()` on Android, passing `androidModule` plus
 * a Koin `androidContext` declaration.
 * - iOS `iOSApp.init` (via a small Swift wrapper), passing `iosModule`.
 *
 * The two platforms intentionally call this *exactly once* per process – Koin's
 * GlobalContext rejects a second `startKoin`. Calling start twice (e.g. in
 * Compose previews) would throw, so we keep the call sites narrow and let the
 * exception surface rather than swallowing it.
 */
fun bootstrap(platformModules: List<Module>, extraConfig: KoinApplication.() -> Unit = {}) {
    Napier.base(DebugAntilog())

    startKoin {
        extraConfig()
        modules(buildList {
            add(com.kfilesync.mobile.di.sharedModule)
            addAll(platformModules)
        })
    }

    Napier.i("KFileSync bootstrap complete (${platformModules.size} platform module(s))")
}