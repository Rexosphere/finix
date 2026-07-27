/**
 * Kafka event-plane contract: Spring Kafka plus the transactional-outbox support
 * from the shared kernel. Topics are declared in docs/api/asyncapi.yaml.
 */
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("spring-kafka").get())
    "testImplementation"(libs.findLibrary("spring-kafka-test").get())
    "integrationTestImplementation"(libs.findLibrary("testcontainers-kafka").get())
}
