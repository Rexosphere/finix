/**
 * Adds the "database per service" persistence contract: JPA + Flyway + PostgreSQL.
 * Each service owns its own database and role; no service reads another's tables.
 */
plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("spring-boot-starter-data-jpa").get())
    "implementation"(libs.findLibrary("flyway-core").get())
    "runtimeOnly"(libs.findLibrary("flyway-postgresql").get())
    "runtimeOnly"(libs.findLibrary("postgresql").get())
    "implementation"(libs.findLibrary("hibernate-types").get())

    "integrationTestImplementation"(libs.findLibrary("testcontainers-postgresql").get())
}

// JPA entities need a no-arg constructor and non-final classes; scope the opening
// narrowly to annotated entities rather than opening the whole codebase.
extensions.configure<org.jetbrains.kotlin.allopen.gradle.AllOpenExtension> {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
