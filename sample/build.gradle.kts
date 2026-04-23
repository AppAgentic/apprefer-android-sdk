plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apprefer.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apprefer.sample"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.4.1"
    }

    // Exercise R8 + our consumer-rules.pro on every release build. If our
    // proguard rules miss something, we catch it here before customers do.
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No real signing config for the sample — use debug signing so
            // `assembleRelease` actually produces an APK we can inspect.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":apprefer"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
