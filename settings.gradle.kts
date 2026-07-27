rootProject.name = "finix"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

// Convention plugins live in an included build so every service shares one build contract.
includeBuild("services/build-logic")

/**
 * Auto-include every JVM module under services/ that carries a build script. Adding a service
 * is therefore "create the directory + build.gradle.kts" — settings never has to be edited,
 * so a new service can never be silently left out of `./gradlew verify`.
 */
file("services").listFiles()
    ?.filter { it.isDirectory && it.name != "build-logic" && File(it, "build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { dir ->
        include(":${dir.name}")
        project(":${dir.name}").projectDir = dir
    }
