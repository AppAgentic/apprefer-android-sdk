import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

// Keep this in lockstep with `Version.kt` — plan §13 requires all four SDKs
// (Flutter / iOS / RN / Android) to ship the same version.
private val sdkVersion = "0.4.1"

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

mavenPublishing {
    coordinates("com.apprefer", "apprefer-android-sdk", sdkVersion)

    pom {
        name.set("AppRefer Android SDK")
        description.set(
            "First-party mobile attribution for Android — part of the AppRefer platform."
        )
        url.set("https://apprefer.com")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("apprefer")
                name.set("AppRefer")
                email.set("support@apprefer.com")
            }
        }

        scm {
            url.set("https://github.com/AppAgentic/apprefer-android-sdk")
            connection.set("scm:git:git://github.com/AppAgentic/apprefer-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/AppAgentic/apprefer-android-sdk.git")
        }
    }

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Signing is required by Maven Central but breaks local-only publishing
    // (`publishToMavenLocal`) when no key is configured. Only sign when the
    // in-memory signing key is present — the GH Actions publish workflow
    // injects it via `ORG_GRADLE_PROJECT_signingInMemoryKey`.
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
}
