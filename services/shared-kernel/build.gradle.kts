plugins {
    id("finix.spring-library")
}

dependencies {
    // The kernel is a library, never a deployable: `api` what consumers legitimately need.
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.validation)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.datatype.jsr310)
    api(libs.kotlin.logging)

    api(libs.bouncycastle.prov)
    api(libs.bouncycastle.pkix)
    api(libs.bouncycastle.util)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.prometheus)
    implementation(libs.resilience4j.spring.boot3)

    // Optional integrations. Every consumer of these is guarded by @ConditionalOnClass, so a
    // service that has no Kafka (enclave-runtime) or no database still starts cleanly — and no
    // service inherits a Redis health check it never asked for.
    compileOnly(libs.spring.kafka)
    compileOnly(libs.spring.boot.starter.data.jpa)
    compileOnly(libs.spring.boot.starter.data.redis)
    compileOnly(libs.spring.boot.starter.oauth2.resource.server)
    compileOnly(libs.spring.boot.starter.security)

    testFixturesApi(libs.kotest.assertions.core)
    testFixturesApi(libs.kotest.property)
    testFixturesApi(libs.archunit.junit5)
    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.testcontainers.junit)
    testFixturesApi(libs.testcontainers.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.data.redis)
    testImplementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.spring.kafka)

    // Security is `compileOnly` above because a consumer without a resource server must still
    // compile against the kernel. The filter chain in SecurityAutoConfiguration is nonetheless
    // the estate's single authorization policy, so the test source set needs the real thing to
    // exercise it end-to-end rather than by inspection.
    testImplementation(libs.spring.boot.starter.security)
    testImplementation(libs.spring.boot.starter.oauth2.resource.server)
    testImplementation(libs.spring.security.test)
    // shared-kernel ships logback-spring.xml, which binds the logstash encoder. Deployables get
    // it from finix.spring-service; the kernel's own Boot-context tests need it too or the
    // context dies during logging init, before any assertion runs.
    testImplementation(libs.logstash.encoder)
}
