plugins {
    id("finix.spring-service")
    id("finix.persistence")
    id("finix.security")
}

dependencies {
    implementation(libs.spring.boot.starter.data.redis)
    // X25519 for hybrid seal (ML-KEM comes via shared-kernel / security plugin).
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    // Credentials come from Vault where it runs (ADR-0006).
    implementation(platform(libs.spring.cloud.bom))
    implementation(libs.spring.cloud.starter.vault.config)
}
