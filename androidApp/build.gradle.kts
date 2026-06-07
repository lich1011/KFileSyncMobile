import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
dependencies {
    implementation(projects.sharedUI)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    //Koin Android - boots the platform DI graph
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(libs.napier)
}

android {
    namespace = "com.kfilesync.mobile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kfilesync.mobile"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
