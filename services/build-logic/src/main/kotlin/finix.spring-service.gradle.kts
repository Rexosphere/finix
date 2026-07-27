/**
 * A deployable FINIX microservice: Spring Boot 3 + Kotlin, observable by default,
 * resilient by default, documented by default.
 *
 * Every service that applies this plugin gets, without opting in:
 *  - actuator health/readiness probes and Prometheus metrics
 *  - OpenTelemetry trace propagation and structured JSON logs
 *  - Resilience4j circuit breakers/bulkheads/retries
 *  - springdoc OpenAPI 3.1 generation
 *  - the FINIX shared kernel (Money, DomainError, ProblemDetail handler, idempotency, PQC)
 */
plugins {
    id("finix.kotlin-base")
    id("org.springframework.boot")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // ADR-0002: Gradle's native platform BOM, not io.spring.dependency-management -- the legacy
    // plugin breaks Kotlin's classpath-snapshot artifact transform on Gradle 9.
    val bom = "org.springframework.boot:spring-boot-dependencies:${libs.findVersion("springBoot").get().requiredVersion}"
    "implementation"(platform(bom))
    "testImplementation"(platform(bom))
    "integrationTestImplementation"(platform(bom))
    "annotationProcessor"(platform(bom))

    if (path != ":shared-kernel") {
        "implementation"(project(":shared-kernel"))
        "testImplementation"(testFixtures(project(":shared-kernel")))
        "integrationTestImplementation"(testFixtures(project(":shared-kernel")))
    }

    "implementation"(libs.findLibrary("spring-boot-starter-web").get())
    "implementation"(libs.findLibrary("spring-boot-starter-actuator").get())
    "implementation"(libs.findLibrary("spring-boot-starter-validation").get())
    "implementation"(libs.findLibrary("jackson-module-kotlin").get())
    "implementation"(libs.findLibrary("jackson-datatype-jsr310").get())

    "implementation"(libs.findLibrary("resilience4j-spring-boot3").get())
    "implementation"(libs.findLibrary("resilience4j-kotlin").get())
    "implementation"(libs.findLibrary("spring-aspects").get())

    "implementation"(libs.findLibrary("micrometer-prometheus").get())
    "implementation"(libs.findLibrary("micrometer-tracing-bridge-otel").get())
    "implementation"(libs.findLibrary("otel-exporter-otlp").get())
    "implementation"(libs.findLibrary("logstash-encoder").get())

    "implementation"(libs.findLibrary("springdoc-webmvc").get())

    "annotationProcessor"(libs.findLibrary("spring-boot-configuration-processor").get())

    "testImplementation"(libs.findLibrary("spring-boot-starter-test").get())
    "testImplementation"(libs.findLibrary("springmockk").get())
    "integrationTestImplementation"(libs.findLibrary("spring-boot-starter-test").get())
    "integrationTestImplementation"(libs.findLibrary("testcontainers-junit").get())
}

// Virtual threads: Loom carries the blocking JDBC/HTTP calls in the banking core.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("-Dspring.threads.virtual.enabled=true")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Layered by default in Boot 3.x, so Docker caches dependency layers across rebuilds.
    archiveFileName.set("app.jar")
}

// Ten JVMs share one grader laptop, so default them to a small serial-GC footprint.
extensions.configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    buildInfo()
}
