import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

// Keep this in lockstep with `Version.kt` — plan §13 requires all four SDKs
// (Flutter / iOS / RN / Android) to ship the same version.
private val sdkVersion = "0.13.3"

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

    buildFeatures {
        buildConfig = false
    }

    testOptions {
        // android.util.Log etc. return defaults in JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }

    // The shared Support translation table, so device tests can check every resource against it.
    sourceSets.getByName("androidTest").assets.srcDir("../Localization")
}

kotlin {
    // Built with Kotlin 2.2 (for :apprefer-billing) but kept consumable by Kotlin 2.0
    // apps: emit 2.0 metadata and depend on the 2.0 stdlib.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
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
