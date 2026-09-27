// kfilesync-core-bindings: production Android binding to kfilesync-core.
//
// Not a KMP module: UniFFI's generated Kotlin wrapper (JNA-based) is
// JVM/Android-only. iOS consumes the same Rust crate through a completely
// different artifact (XCFramework + Swift bindings via cinterop), which is
// out of scope here (see plan: no full Xcode on this machine yet).
//
// Build approach: this module was originally meant to use the
// `org.mozilla.rust-android-gradle.rust-android` plugin to drive cargo-ndk
// directly from Gradle, but that plugin is incompatible with AGP 9.1: as
// soon as its `cargo { module = ... }` block is configured, the plugin's
// own afterEvaluate logic does an extension lookup
// (`Extension of type 'LibraryExtension' does not exist. Currently
// registered extension types: [..., LibraryExtensionImpl, ...]'`) that
// AGP 9.1's extension registration no longer satisfies. This is a genuine
// plugin/AGP incompatibility (reproduced with a minimal android {} block
// and no other configuration), not a mistake in this module's setup — see
// the pre-approved fallback in the plan. So instead we shell out to the
// sibling repo's own build-android.sh, which already drives cargo-ndk +
// uniffi-bindgen directly and is verified to work.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.kfilesync.mobile.core.bindings"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("../../KFileSyncCore/build/android/jniLibs")
            kotlin.srcDir("../../KFileSyncCore/build/android/kotlin")
        }
    }
}

// The sibling kfilesync-core repo, checked out next to this repo (same
// layout the existing androidHostTest conformance wiring in
// sharedLogic/build.gradle.kts already relies on).
val coreRepoDir = rootProject.file("../KFileSyncCore")

val buildAndroidCore by tasks.registering(Exec::class) {
    workingDir = coreRepoDir
    environment("ANDROID_NDK_HOME", System.getenv("ANDROID_NDK_HOME") ?: "")
    commandLine("bash", "scripts/build-android.sh", "dev")

    inputs.dir(coreRepoDir.resolve("kfilesync-core/src"))
    inputs.file(coreRepoDir.resolve("kfilesync-core/Cargo.toml"))
    outputs.dir(coreRepoDir.resolve("build/android"))
}

tasks.matching { it.name.contains("Kotlin") && it.name.contains("compile", ignoreCase = true) }
    .configureEach { dependsOn(buildAndroidCore) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(buildAndroidCore) }

dependencies {
    // JNA's Android-packaged artifact — the `@aar` classifier is required so
    // it ships jniLibs instead of the desktop-only .so/.dll/.dylib triplet.
    api("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}