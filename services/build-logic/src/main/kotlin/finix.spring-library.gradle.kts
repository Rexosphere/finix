/**
 * A shared Kotlin library on the Spring classpath but not itself deployable
 * (no bootJar, no main class). Used by `shared-kernel`.
 */
plugins {
    id("finix.kotlin-base")
    id("org.jetbrains.kotlin.plugin.spring")
    `java-test-fixtures`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val bom = "org.springframework.boot:spring-boot-dependencies:${libs.findVersion("springBoot").get().requiredVersion}"
    "api"(platform(bom))
    "testFixturesApi"(platform(bom))
    "testImplementation"(platform(bom))
    "integrationTestImplementation"(platform(bom))
}
