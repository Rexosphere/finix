package org.finix.loan.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class LoanConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
