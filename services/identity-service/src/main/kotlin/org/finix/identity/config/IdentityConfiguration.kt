package org.finix.identity.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock

@Configuration
@EnableConfigurationProperties(KeycloakProperties::class)
class IdentityConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()
}
