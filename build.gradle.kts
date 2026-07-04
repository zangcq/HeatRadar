// Root build script for HeatRadar.
// Module-level configuration lives in app/build.gradle.kts.
plugins {
    // Applied here to satisfy Android Studio sync expectations.
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.0.21" apply false
}
