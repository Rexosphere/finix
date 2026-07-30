package org.finix.kernel.idempotency

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Tuning for [IdempotencyFilter]. The defaults target a banking retry window rather than a
 * generic API: 24 hours comfortably outlives a mobile client that queues a transfer overnight
 * while its user is out of coverage, and outlives every Resilience4j retry budget in the mesh.
 */
@ConfigurationProperties(prefix = "finix.idempotency")
data class IdempotencyProperties(
    /** Disabling this is a deliberate, auditable act — hence a property rather than a profile. */
    val enabled: Boolean = true,
    val ttl: Duration = DEFAULT_TTL,
    /**
     * Paths exempt from the header requirement. Only infrastructure and protocol-constrained
     * endpoints belong here: probes are not mutating, and the USSD gateway cannot set headers
     * because the telco owns the request shape (it carries its own `sessionId` instead).
     */
    val excludedPaths: List<String> = listOf("/actuator", "/internal", "/ussd"),
) {
    companion object {
        /** Long enough to outlive an offline PWA queue held overnight, and every retry budget. */
        val DEFAULT_TTL: Duration = Duration.ofHours(24)
    }
}
