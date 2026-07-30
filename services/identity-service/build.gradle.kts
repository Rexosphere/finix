plugins {
    id("finix.spring-service")
    id("finix.persistence")
    id("finix.security")
    id("finix.messaging")
}

dependencies {
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.oauth2.client)
}
