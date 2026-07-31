plugins {
    id("finix.spring-service")
}

dependencies {
    // Explicit for the reconstruct-only crypto path; also transitively available via shared-kernel.
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
}
