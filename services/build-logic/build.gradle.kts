import org.gradle.api.attributes.plugin.GradlePluginApiVersion
import org.gradle.util.GradleVersion

plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugin implementation artifacts, so precompiled script plugins can `apply` them.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-noarg:${libs.versions.kotlin.get()}")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${libs.versions.springBoot.get()}")
    // detekt 1.23.x is built against the Kotlin 1.9 compiler. Left on this classloader it
    // shadows KGP's own 2.x compiler and breaks incremental compilation. detekt resolves the
    // compiler it actually analyses with from its own `detekt` configuration at execution time,
    // so removing it from the *plugin* classpath is safe.
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
    }
}

kotlin {
    jvmToolchain(21)
}

/**
 * Gradle plugins publish per-Gradle-version variants. Inside an included build the
 * `org.gradle.plugin.api-version` attribute is not inferred, so KGP silently resolves its
 * legacy `gradle813` variant -- whose incremental-compilation artifact transform is wired for
 * an older compiler and fails with NoClassDefFoundError on ClasspathEntrySnapshotter.
 * Requesting the running Gradle's version selects the matching modern variant.
 */
val runningGradle = objects.named(GradlePluginApiVersion::class.java, GradleVersion.current().version)
configurations.matching { it.name in setOf("compileClasspath", "runtimeClasspath") }.configureEach {
    attributes {
        attribute(GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE, runningGradle)
    }
}


