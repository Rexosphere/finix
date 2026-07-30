package org.finix.identity.adapter.`in`.rest

import org.finix.kernel.domain.DomainError
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Resolves the Keycloak subject from the current security context.
 *
 * When [finix.security.permit-all] is true and no JWT is present (local demo), callers may pass
 * `X-Finix-User` with the seeded persona keycloak id.
 */
object CurrentUser {
    const val DEMO_USER_HEADER = "X-Finix-User"

    fun keycloakUserId(demoHeader: String? = null): String {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        val jwtSub = when (authentication) {
            is JwtAuthenticationToken -> authentication.token.subject
            else -> (authentication?.principal as? Jwt)?.subject
        }
        if (!jwtSub.isNullOrBlank()) {
            return jwtSub
        }
        if (!demoHeader.isNullOrBlank()) {
            return demoHeader
        }
        DomainError.Forbidden("authenticated subject required").raise()
    }
}
