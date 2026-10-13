plugins {
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    // Kotlin 2.2: Play Billing 8.3 (used by :apprefer-billing) ships Kotlin 2.2
    // metadata. :apprefer still targets language/API 2.0 and stdlib 2.0.21, so
    // attribution-only apps on Kotlin 2.0 keep working.
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // Vanniktech plugin — applied in `apprefer/build.gradle.kts`. Handles the
    // full Sonatype / Maven Central publish flow: signing, POM, Javadoc jar,
    // sources jar, and the actual bundle upload.
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}
