package org.finix.compliance.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ComplianceConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
