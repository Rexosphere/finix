plugins {
    id("finix.spring-service")
    id("finix.security")
}

dependencies {
    implementation(libs.spring.boot.starter.data.redis)
}
