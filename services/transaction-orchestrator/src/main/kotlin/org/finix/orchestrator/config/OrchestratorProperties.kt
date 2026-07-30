package org.finix.orchestrator.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "finix")
data class OrchestratorProperties(
    val account: Downstream = Downstream(baseUrl = "http://localhost:8083"),
    val ledger: Downstream = Downstream(baseUrl = "http://localhost:8084"),
) {
    data class Downstream(
        val baseUrl: String = "",
    )
}
