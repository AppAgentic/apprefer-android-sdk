import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

// Same version as :apprefer (and the other three SDKs).
private val sdkVersion = "0.13.3"

android {
    namespace = "com.apprefer.sdk.billing"
    compileSdk = 35

    defaultConfig {
        // Play Billing Library 8 requires API 23.
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("com.apprefer.sdk.InternalAppReferApi")
    }
}

dependencies {
    api(project(":apprefer"))
    api("com.android.billingclient:billing-ktx:8.3.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json: the android.jar stub returns defaults.
    testImplementation("org.json:json:20240303")
}

mavenPublishing {
    coordinates("com.apprefer", "apprefer-billing", sdkVersion)

    pom {
        name.set("AppRefer Billing for Android")
        description.set(
            "Google Play subscriptions and entitlements verified by AppRefer — a RevenueCat replacement."
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

    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
}
