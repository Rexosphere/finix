/**
 * Zero-trust service contract (blueprint Layer 3): OAuth2 resource server validating
 * short-lived Keycloak JWTs, DPoP sender-constraining, and mutual TLS between services.
 */
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("spring-boot-starter-security").get())
    "implementation"(libs.findLibrary("spring-boot-starter-oauth2-resource-server").get())
    "implementation"(libs.findLibrary("bouncycastle-prov").get())
    "implementation"(libs.findLibrary("bouncycastle-pkix").get())
    "implementation"(libs.findLibrary("bouncycastle-util").get())

    "testImplementation"(libs.findLibrary("spring-security-test").get())
    "integrationTestImplementation"(libs.findLibrary("testcontainers-keycloak").get())
}
