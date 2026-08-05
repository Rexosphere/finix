package org.finix.vault.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@ConfigurationProperties(prefix = "finix.enclave")
data class EnclaveProperties(
    /** Always `remote`: the enclave is a separate process reached over HTTP. */
    val mode: String = "remote",
    val baseUrl: String = "http://localhost:8090",
    val kemPublicKeyBase64: String = "",
    val x25519PublicKeyBase64: String = "",
)

@Configuration
@EnableConfigurationProperties(EnclaveProperties::class)
class VaultConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
