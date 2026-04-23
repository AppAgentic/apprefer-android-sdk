plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apprefer.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Install Referrer — THE Android deterministic attribution signal.
    // Ported from Flutter plugin (AppReferPlugin.kt). `api` so consumers don't need to
    // manually add it to pick up classes they'd hit via consumer-rules.pro -dontwarn.
    api("com.android.installreferrer:installreferrer:2.2")

    // Google Advertising ID. Ported from Flutter plugin lines 52–69.
    // Optional signal — we catch all errors if it's missing on the host device.
    api("com.google.android.gms:play-services-ads-identifier:18.1.0")
}
