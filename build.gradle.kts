plugins {
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Vanniktech plugin — applied in `apprefer/build.gradle.kts`. Handles the
    // full Sonatype / Maven Central publish flow: signing, POM, Javadoc jar,
    // sources jar, and the actual bundle upload.
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}
