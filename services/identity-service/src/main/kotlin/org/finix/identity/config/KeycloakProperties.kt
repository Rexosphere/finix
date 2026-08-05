package org.finix.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Keycloak realm coordinates for the PKCE BFF helpers. */
@ConfigurationProperties(prefix = "finix.keycloak")
data class KeycloakProperties(
    /** Internal base URL used for server-side token exchange. */
    val baseUrl: String = "http://localhost:8081",
    /**
     * Host-reachable base URL returned to browsers for the authorize redirect.
     * In compose this stays `http://localhost:8081` while [baseUrl] is `http://keycloak:8080`.
     */
    val publicBaseUrl: String = "http://localhost:8081",
    val realm: String = "finix",
    val clientId: String = "finix-web",
) {
    fun authorizationEndpoint(): String =
        "$publicBaseUrl/realms/$realm/protocol/openid-connect/auth"

    fun tokenEndpoint(): String =
        "$baseUrl/realms/$realm/protocol/openid-connect/token"
}
