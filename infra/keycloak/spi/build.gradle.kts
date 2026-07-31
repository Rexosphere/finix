plugins {
    java
}

group = "org.finix"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.keycloak:keycloak-server-spi:26.0.0")
    compileOnly("org.keycloak:keycloak-server-spi-private:26.0.0")
    compileOnly("org.keycloak:keycloak-services:26.0.0")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("finix-adaptive-auth")
}
