package org.finix.orchestrator.config

import io.netty.channel.ChannelOption
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Clock
import java.time.Duration

@Configuration
@EnableConfigurationProperties(OrchestratorProperties::class)
class OrchestratorConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun accountWebClient(builder: WebClient.Builder, properties: OrchestratorProperties): WebClient =
        builder
            .baseUrl(properties.account.baseUrl)
            .clientConnector(ReactorClientHttpConnector(timeoutClient()))
            .build()

    @Bean
    fun ledgerWebClient(builder: WebClient.Builder, properties: OrchestratorProperties): WebClient =
        builder
            .baseUrl(properties.ledger.baseUrl)
            .clientConnector(ReactorClientHttpConnector(timeoutClient()))
            .build()

    private fun timeoutClient(): HttpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val RESPONSE_TIMEOUT_SECONDS = 5L
    }
}
