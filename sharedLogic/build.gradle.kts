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

    android {
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

        getByName("androidHostTest") {
            // Conformance runner (1.3): the UniFFI-generated Kotlin wrapper
            // (kfilesync-conformance's Kotlin counterpart to
            // rust-runner/src/main.rs) needs JNA to load the host-native
            // cdylib, and only the JVM-run host test target can do that -
            // commonTest also compiles for iOS, where there is no JNA/JVM.
            kotlin.srcDir(rootProject.file("../KFileSyncCore/build/android/kotlin"))
            dependencies {
                implementation(libs.jna)
            }
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
            implementation(libs.kotlinx.atomicfu)
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

// Conformance runner (1.3) wiring: point the host-JVM test at kfilesync-core's
// sibling checkout so it can (a) load the host-native cdylib via JNA and
// (b) read the shared fixtures without copying either into this repo.
// KFileSyncCore's `target/debug` dylib and `build/android/kotlin` bindings
// come from `./scripts/build-android.sh dev` and are gitignored there - a
// missing directory here just means that script hasn't been run yet.
val coreRepoDir = rootProject.file("../KFileSyncCore")
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    this as Test
    systemProperty("jna.library.path", coreRepoDir.resolve("target/debug").absolutePath)
    systemProperty("conformance.fixtures.dir", coreRepoDir.resolve("kfilesync-conformance/fixtures").absolutePath)
}