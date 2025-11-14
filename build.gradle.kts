// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Force the Kotlin plugin version to match the one required by new libraries.
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
}
