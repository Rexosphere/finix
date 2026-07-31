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
}
