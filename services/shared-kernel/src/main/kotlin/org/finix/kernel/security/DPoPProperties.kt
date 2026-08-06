package org.finix.kernel.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "finix.dpop")
data class DPoPProperties(
    /** Master switch — off for local unit tests that do not mint proofs. */
    val enabled: Boolean = true,
    /**
     * When true, every authenticated request must carry a valid `DPoP` header.
     * Default false so Bearer-only demos still work; production compose sets true.
     */
    val required: Boolean = false,
    val maxAgeSeconds: Long = 60,
    val clockSkewSeconds: Long = 5,
    /**
     * Paths that never carry a sender-constrained token, so a proof cannot be expected: probes,
     * API docs, and the token endpoints that run *before* a token exists.
     *
     * `/api/v1/admin/seed` was removed — it is an ordinary authenticated route, and exempting it
     * meant a stolen bearer token could seed without the matching private key even after DPoP was
     * made mandatory. This does not make DPoP *required* anywhere new: [DPoPFilter] still skips
     * every request when [enabled] is false, and still passes proof-less requests through unless
     * [required] is true and the caller is already JWT-authenticated.
     */
    val excludePathPrefixes: List<String> = listOf(
        "/actuator",
        "/v3/api-docs",
        "/swagger-ui",
        "/api/v1/auth",
    ),
)
