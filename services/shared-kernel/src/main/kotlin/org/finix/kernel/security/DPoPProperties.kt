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
    val excludePathPrefixes: List<String> = listOf(
        "/actuator",
        "/v3/api-docs",
        "/swagger-ui",
        "/api/v1/admin/seed",
        "/api/v1/auth",
    ),
)
