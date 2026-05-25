// sharedUI - Compose Multiplatform UI module.
//
// Owns: Compose composables (App, screens, components, theme, navigation),
// the iOS `MainViewController` entry point, and re-exports sharedLogic
// types via `api(projects.sharedLogic)` so the iOS framework consumer
// (Swift) can see them.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedUI"
            isStatic = true

            // Re-export sharedLogic into the iOS framework so Swift can
            // see domain types (DeviceId, Share, etc.) when needed.
            export(projects.sharedLogic)
        }
    }

    androidLibrary {
        namespace = "com.kfilesync.mobile.sharedUI"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api() - both sharedUI consumers (androidApp) AND the iOS framework
            // need to see sharedLogic's public types.
            api(projects.sharedLogic)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)

            // DI - Compose-friendly bindings (`koinInject` etc.)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)

            // Navigation - Voyager is wired in Phase 1 (T1.8). The library is
            // listed in libs.versions.toml so plumbing is one alias add away;
            // we omit it from the Phase 0 dependency graph to keep the
            // surface minimal until the four-tab navigation lands.
             implementation(libs.voyager.navigator)
             implementation(libs.voyager.tabNavigator)
             implementation(libs.voyager.koin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
        }
    }
}

dependencies {
    // ui-tooling is a runtime-only Android dep - the new Android KMP library
    // plugin doesn't support build variants, so we add it to androidRuntimeClasspath
    // instead of debugImplementation.
    androidRuntimeClasspath(libs.compose.uiTooling)
}