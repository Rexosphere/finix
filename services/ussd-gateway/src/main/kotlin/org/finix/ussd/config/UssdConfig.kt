package org.finix.ussd.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock

@ConfigurationProperties(prefix = "finix.account")
data class AccountClientProperties(val baseUrl: String = "http://localhost:8083")

@ConfigurationProperties(prefix = "finix.orchestrator")
data class OrchestratorClientProperties(val baseUrl: String = "http://localhost:8085")

@ConfigurationProperties(prefix = "finix.ussd")
data class UssdProperties(
    val serviceCode: String = "*334#",
    val sessionTtlSeconds: Long = 120,
)

@Configuration
@EnableConfigurationProperties(
    AccountClientProperties::class,
    OrchestratorClientProperties::class,
    UssdProperties::class,
)
class UssdConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()
}
