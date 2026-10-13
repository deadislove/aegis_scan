allprojects {
    repositories {
        google()
        mavenCentral()
        // The real aegisscan-core/aegisscan-camera, published via
        // `./gradlew :aegisscan-core:publishToMavenLocal :aegisscan-camera:publishToMavenLocal`
        // from the repo's android/ project -- see
        // spec/sample/react-native.md's "Why mavenLocal, not includeBuild"
        // for why. Needed here (not just in the plugin's own build.gradle)
        // because Gradle resolves a consuming project's classpath (:app,
        // which depends on the aegis_scan_flutter plugin module) using the
        // consuming project's own repositories, not the dependency's.
        mavenLocal()
    }
}

val newBuildDir: Directory = rootProject.layout.buildDirectory.dir("../../build").get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
