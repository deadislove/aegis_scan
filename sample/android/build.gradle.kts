// AegisScan Android sample: a minimal CameraX Preview + AndroidCameraScannerEngine
// integration. See ../../android for the SDK modules this depends on, and
// README.md in this directory for how it's wired into that Gradle build.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aegisscan.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aegisscan.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(project(":aegisscan-camera"))
    implementation(project(":aegisscan-core"))

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Needed directly because aegisscan-camera depends on these as
    // `implementation` (not `api`), which hides them from our compile
    // classpath even though they're already on the runtime classpath.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
