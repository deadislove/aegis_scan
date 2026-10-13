plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

android {
    namespace = "com.aegisscanrnsample.bridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.aegisscan:aegisscan-core:1.0.0")
    implementation("com.aegisscan:aegisscan-camera:1.0.0")

    // Needed directly because aegisscan-camera depends on these as
    // `implementation` (not `api`), same as sample/android/build.gradle.kts.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    compileOnly("com.facebook.react:react-android")
}
