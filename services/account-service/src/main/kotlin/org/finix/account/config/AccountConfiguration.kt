package org.finix.account.config

import org.finix.account.domain.OfflinePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@ConfigurationProperties(prefix = "finix.offline")
data class OfflineProperties(
    val maxDurationHours: Long = 72,
    val maxPerTxMinor: Long = 500_000,
    val maxCumulativeMinor: Long = 2_500_000,
    val maxQueued: Int = 20,
    val reconciliationWindowHours: Long = 168,
) {
    fun toPolicy(): OfflinePolicy = OfflinePolicy(
        maxDurationHours = maxDurationHours,
        maxPerTxMinor = maxPerTxMinor,
        maxCumulativeMinor = maxCumulativeMinor,
        maxQueued = maxQueued,
        reconciliationWindowHours = reconciliationWindowHours,
    )
}

@Configuration
@EnableConfigurationProperties(OfflineProperties::class)
class AccountConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
