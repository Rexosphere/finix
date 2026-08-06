package org.finix.kernel.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Authorization policy tests for the shared [SecurityAutoConfiguration] filter chain.
 *
 * These drive the **real** chain — the same `finixSecurityFilterChain` bean every FINIX service
 * inherits — through MockMvc, rather than asserting on the configuration source.
 *
 * The invariant under test: `/api/v1/admin/seed` is a demo fixture, not infrastructure. It must
 * follow whatever policy the estate is currently running. A hardcoded `permitAll()` for it would
 * survive `finix.security.permit-all=false`, so a future production hardening would *appear*
 * to close the estate while this one route stayed anonymous.
 */
private const val SEED_PATH = "/api/v1/admin/seed"
private const val PROTECTED_PATH = "/api/v1/accounts"
private const val PUBLIC_HEALTH_PATH = "/actuator/health"
private const val PUBLIC_AUTH_PATH = "/api/v1/auth/token"

/**
 * Auto-configurations excluded because this suite is about the filter chain, not persistence:
 * without them a missing DataSource would fail the context before a single request is dispatched.
 */
private const val EXCLUDED_AUTOCONFIG =
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"

@SpringBootConfiguration
@EnableAutoConfiguration
class SeedAuthorizationTestApp {

    /**
     * The resource server needs a decoder bean to start. No test here presents a real token —
     * authenticated cases use the `jwt()` post-processor, which bypasses decoding entirely — so
     * rejecting everything is both sufficient and honest about what this stub does.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder = JwtDecoder { throw BadJwtException("no real tokens in this suite") }

    @Bean
    fun stubEndpoints(): StubEndpoints = StubEndpoints()
}

/**
 * Stands in for the seed controllers that account-service and identity-service really expose,
 * so the chain is exercised against the exact paths that ship.
 */
@RestController
class StubEndpoints {
    /**
     * Bodies live as properties rather than inline literals so each handler returns a reference
     * instead of a constant expression. The response text is unchanged — only the chain's
     * authorization decision is under test, never the payload.
     */
    private val seedBody = "seeded"
    private val accountsBody = "accounts"
    private val tokenBody = "token"

    @PostMapping(SEED_PATH)
    fun seed(): String = seedBody

    @GetMapping(PROTECTED_PATH)
    fun accounts(): String = accountsBody

    @PostMapping(PUBLIC_AUTH_PATH)
    fun token(): String = tokenBody
}

/**
 * The production-shaped posture: authentication is switched on.
 */
@SpringBootTest(
    classes = [SeedAuthorizationTestApp::class],
    properties = [
        "finix.security.permit-all=false",
        // Idempotency would answer a keyless POST with 400 before authorization is reached; this
        // suite is about who may call, not about replay protection.
        "finix.idempotency.enabled=false",
        "spring.autoconfigure.exclude=$EXCLUDED_AUTOCONFIG",
    ],
)
@AutoConfigureMockMvc
class SeedEndpointLockedDownTest @Autowired constructor(private val mockMvc: MockMvc) {

    /**
     * CASE 1 — the bypass. An anonymous caller must not reach the seed endpoint once the estate
     * is locked down. Any 2xx here means the demo backdoor outlived the hardening switch.
     */
    @Test
    fun `case 1 - anonymous seed is rejected when permit-all is false`() {
        mockMvc.perform(post(SEED_PATH))
            .andExpect(status().isUnauthorized)
    }

    /** CASE 2 — liveness probes stay public, so hardening does not break orchestration. */
    @Test
    fun `case 2 - health remains public when permit-all is false`() {
        mockMvc.perform(get(PUBLIC_HEALTH_PATH))
            .andExpect(status().isOk)
    }

    /** CASE 3 — the control: an ordinary business route is already protected. */
    @Test
    fun `case 3 - a normal protected api requires authentication when permit-all is false`() {
        mockMvc.perform(get(PROTECTED_PATH))
            .andExpect(status().isUnauthorized)
    }

    /**
     * Seed must be *authenticated*, not unreachable. Removing the bypass has to leave a
     * legitimate caller able to run it, otherwise the fix has broken the feature.
     */
    @Test
    fun `authenticated caller may still reach seed when permit-all is false`() {
        mockMvc.perform(post(SEED_PATH).with(jwt()))
            .andExpect(status().isOk)
    }

    /** The token endpoint is genuinely pre-authentication and keeps its explicit exemption. */
    @Test
    fun `auth endpoints stay public when permit-all is false`() {
        mockMvc.perform(post(PUBLIC_AUTH_PATH))
            .andExpect(status().isOk)
    }
}

/**
 * The current demo posture, which every service ships today. Nothing in this task may change it —
 * the estate-wide rollout to authenticated mode is a separate piece of work.
 */
@SpringBootTest(
    classes = [SeedAuthorizationTestApp::class],
    properties = [
        "finix.security.permit-all=true",
        "finix.idempotency.enabled=false",
        "spring.autoconfigure.exclude=$EXCLUDED_AUTOCONFIG",
    ],
)
@AutoConfigureMockMvc
class SeedEndpointPermitAllTest @Autowired constructor(private val mockMvc: MockMvc) {

    /** CASE 4 — the demo stack keeps working untouched. */
    @Test
    fun `case 4 - anonymous seed still succeeds when permit-all is true`() {
        mockMvc.perform(post(SEED_PATH))
            .andExpect(status().isOk)
    }

    @Test
    fun `case 4 - ordinary apis stay open when permit-all is true`() {
        mockMvc.perform(get(PROTECTED_PATH))
            .andExpect(status().isOk)
    }

    @Test
    fun `case 4 - health stays public when permit-all is true`() {
        mockMvc.perform(get(PUBLIC_HEALTH_PATH))
            .andExpect(status().isOk)
    }
}
