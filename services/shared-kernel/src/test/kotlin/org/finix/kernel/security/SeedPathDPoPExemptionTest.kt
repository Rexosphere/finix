package org.finix.kernel.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

/**
 * Guards the DPoP half of the seed-endpoint fix.
 *
 * DPoP and authentication are separate controls, and removing `/api/v1/admin/seed` from
 * [DPoPProperties.excludePathPrefixes] must tighten only the first without accidentally demanding
 * a proof in deployments that have DPoP switched off. These tests pin both directions.
 */
private const val SEED_URI = "/api/v1/admin/seed"

private fun bearerAuthenticatedJwt(): JwtAuthenticationToken {
    val jwt = Jwt.withTokenValue("token-value")
        .header("alg", "RS256")
        .claim("sub", "seed-caller")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build()
    return JwtAuthenticationToken(jwt)
}

/** Runs [filter] over a POST to [SEED_URI] and reports whether the request reached the chain. */
private fun reachedChain(filter: DPoPFilter, authenticated: Boolean): Boolean {
    val request = MockHttpServletRequest("POST", SEED_URI)
    request.requestURI = SEED_URI
    val response = MockHttpServletResponse()
    val chain: FilterChain = MockFilterChain()
    if (authenticated) {
        SecurityContextHolder.getContext().authentication = bearerAuthenticatedJwt()
    }
    return try {
        filter.doFilter(request, response, chain)
        response.status != 401
    } finally {
        SecurityContextHolder.clearContext()
    }
}

class SeedPathDPoPExemptionTest : StringSpec({

    "seed is no longer exempt: an authenticated proof-less call is rejected when DPoP is required" {
        val filter = DPoPFilter(DPoPProperties(enabled = true, required = true))
        reachedChain(filter, authenticated = true) shouldBe false
    }

    "removing the exemption does not require DPoP when DPoP is globally disabled" {
        val filter = DPoPFilter(DPoPProperties(enabled = false, required = true))
        reachedChain(filter, authenticated = true) shouldBe true
    }

    "removing the exemption does not require DPoP when proofs are merely optional" {
        val filter = DPoPFilter(DPoPProperties(enabled = true, required = false))
        reachedChain(filter, authenticated = true) shouldBe true
    }

    "an anonymous proof-less call is never rejected by DPoP — authentication is a separate control" {
        val filter = DPoPFilter(DPoPProperties(enabled = true, required = true))
        reachedChain(filter, authenticated = false) shouldBe true
    }

    "the exemption list no longer contains the seed path" {
        DPoPProperties().excludePathPrefixes.none { SEED_URI.startsWith(it) } shouldBe true
    }

    "genuinely pre-authentication paths keep their exemption" {
        val prefixes = DPoPProperties().excludePathPrefixes
        prefixes.any { "/api/v1/auth/token".startsWith(it) } shouldBe true
        prefixes.any { "/actuator/health".startsWith(it) } shouldBe true
    }
})
