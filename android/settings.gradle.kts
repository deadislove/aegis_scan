pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-download a matching JDK (e.g. 17) when jvmToolchain()
    // requests one that isn't installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "aegisscan-android"
include(":aegisscan-core")    // Pure Kotlin/JVM: Luhn + SpatialMatcher (no Android deps).
include(":aegisscan-camera")  // Android library: CameraX + ML Kit engine.

// Sample app lives under sample/ at the repo root (not android/) so every
// platform's sample is grouped together; it's wired into this build via a
// custom projectDir.
include(":sample-android")
project(":sample-android").projectDir = file("../sample/android")
