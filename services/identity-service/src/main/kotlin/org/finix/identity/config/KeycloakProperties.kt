package org.finix.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Keycloak realm coordinates for the PKCE BFF helpers. */
@ConfigurationProperties(prefix = "finix.keycloak")
data class KeycloakProperties(
    val baseUrl: String = "http://localhost:8081",
    val realm: String = "finix",
    val clientId: String = "finix-web",
) {
    fun authorizationEndpoint(): String =
        "$baseUrl/realms/$realm/protocol/openid-connect/auth"

    fun tokenEndpoint(): String =
        "$baseUrl/realms/$realm/protocol/openid-connect/token"
}
