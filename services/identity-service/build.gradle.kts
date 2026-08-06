plugins {
    id("finix.spring-service")
    id("finix.persistence")
    id("finix.security")
    id("finix.messaging")
}

dependencies {
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.oauth2.client)

    // Credentials come from Vault where it runs (ADR-0006). Platform BOM rather
    // than the dependency-management plugin, per ADR-0002.
    implementation(platform(libs.spring.cloud.bom))
    implementation(libs.spring.cloud.starter.vault.config)
}
