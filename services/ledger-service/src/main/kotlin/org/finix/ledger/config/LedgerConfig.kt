package org.finix.ledger.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class LedgerConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
