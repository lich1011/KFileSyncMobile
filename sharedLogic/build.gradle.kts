// sharedLogic - Kotlin Multiplatform module: domain, application, infrastructure layers.
//
// Owns: pure business logic, repositories, ports/adapters, SQLDelight schema,
// Ktor HTTP server/client, Koin DI module, platform key store / discovery / file
// watcher / background-sync adapters.
//
// Does NOT own: any Compose UI. Compose lives in sharedUI.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // applyDefaultHierarchyTemplate() is implicit in Kotlin 2.x - `iosMain`
    // aggregates iosX64Main / iosArm64Main / iosSimulatorArm64Main automatically.

    // kotlin.time.Instant / Clock are still gated by @ExperimentalTime in 2.3.x.
    // Apply globally to commonMain doesn't need per-file @OptIn annotations.
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    optIn.add("kotlin.time.ExperimentalTime")
                }
            }
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "com.kfilesync.mobile.sharedLogic"
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
            // Kotlin & async
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.kotlinx.atomicfu)

            // Persistence
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutinesExtensions)

            // Networking (Lansync v1 REST surface - both client and server live here
            // so the protocol code stays in one place and is unit-testable).
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.contentNegotiation)
            implementation(libs.ktor.server.cors)
            implementation(libs.ktor.server.statusPages)

            // DI
            implementation(libs.koin.core)

            // Logging
            implementation(libs.napier)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.androidDriver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.server.netty)
            implementation(libs.koin.android)
            implementation(libs.androidx.work.runtime)
            implementation(libs.bouncycastle.bcpkix)
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.lifecycle.process)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.nativeDriver)
            implementation(libs.ktor.client.darwin)
//            implementation(libs.kotlinx.atomicfu)
        }
    }
}

sqldelight {
    databases {
        create("KFileSyncDatabase") {
            packageName.set("com.kfilesync.mobile.db")
        }
    }
}