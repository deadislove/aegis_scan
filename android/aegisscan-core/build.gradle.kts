plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

// Lets a separate Gradle/AGP toolchain (e.g. sample/react-native/android,
// which can't share this build's Gradle version via includeBuild -- see
// that project's settings.gradle for why) depend on this module via
// mavenLocal() as plain "com.aegisscan:aegisscan-core:1.0.0" coordinates.
// Run `./gradlew :aegisscan-core:publishToMavenLocal` after any change
// this sample needs to pick up.
group = "com.aegisscan"
version = "1.0.0"

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.aegisscan"
            artifactId = "aegisscan-core"
            version = project.version.toString()
        }
    }
}
