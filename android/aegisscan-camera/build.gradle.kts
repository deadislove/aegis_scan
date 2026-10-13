plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Lets a separate Gradle/AGP toolchain (e.g. sample/react-native/android,
// which can't share this build's Gradle version via includeBuild -- see
// that project's settings.gradle for why) depend on this module via
// mavenLocal() as plain "com.aegisscan:aegisscan-camera:1.0.0" coordinates.
// Run `./gradlew :aegisscan-camera:publishToMavenLocal` after any change
// this sample needs to pick up.
group = "com.aegisscan"
version = "1.0.0"

android {
    namespace = "com.aegisscan.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    testOptions { unitTests.all { it.useJUnitPlatform() } }

    publishing {
        singleVariant("release")
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.aegisscan"
                artifactId = "aegisscan-camera"
                version = project.version.toString()
            }
        }
    }
}

dependencies {
    api(project(":aegisscan-core"))

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Bundled models: no runtime model download, works fully offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
