// kfilesync-core-bindings: production Android binding to kfilesync-core.
//
// 方案 3 改造：直接依赖预编译好的 kfilesync-core AAR 制品，
// 解除对外部兄弟目录 build-android.sh 及本地 Rust/NDK 工具链的强耦合。

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
}

dependencies {
    // 消费预编译好的 kfilesync-core AAR 制品
    api(libs.kfilesync.core.android)

    // JNA's Android-packaged artifact — the `@aar` classifier is required so
    // it ships jniLibs instead of the desktop-only .so/.dll/.dylib triplet.
    api("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}