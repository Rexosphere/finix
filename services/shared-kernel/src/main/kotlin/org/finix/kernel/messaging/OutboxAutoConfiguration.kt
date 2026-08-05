package org.finix.kernel.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling

/** Relay tuning. Defaults trade a little latency for far fewer wasted queries on an idle system. */
@ConfigurationProperties(prefix = "finix.outbox")
data class OutboxProperties(
    val enabled: Boolean = true,
    val pollIntervalMs: Long = 500,
    /** Small enough that one slow send cannot hold the claiming transaction open for long. */
    val batchSize: Int = 100,
    val sendTimeoutMs: Long = 5_000,
    /** Attempts before a failure is logged at ERROR and alerted on rather than merely warned. */
    val alertAfterAttempts: Int = 5,
)

/**
 * Wires the transactional outbox into any service that has both a datasource and Kafka.
 *
 * A service without Kafka on the classpath (say `enclave-runtime`, which is deliberately
 * network-isolated) simply does not get a relay, and nothing fails to start. That is why the
 * conditions are on classes and beans rather than on a profile.
 */
// The @ConditionalOnBean checks below can only see beans that were registered before this
// class is evaluated, and auto-configurations are otherwise ordered by class name -- which puts
// `org.finix` ahead of `org.springframework`. Without this the JdbcTemplate and KafkaTemplate
// beans do not exist yet and the relay silently never gets wired.
@AutoConfiguration(after = [JdbcTemplateAutoConfiguration::class, KafkaAutoConfiguration::class])
@EnableConfigurationProperties(OutboxProperties::class)
@ConditionalOnClass(KafkaTemplate::class, NamedParameterJdbcTemplate::class)
@ConditionalOnProperty(prefix = "finix.outbox", name = ["enabled"], matchIfMissing = true)
// Scheduling is switched on here rather than in each service: the relay is the reason any of
// these services needs a scheduler at all, so the two arrive together or not at all.
@EnableScheduling
class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnBean(NamedParameterJdbcTemplate::class)
    @ConditionalOnMissingBean
    fun jdbcOutbox(
        jdbc: NamedParameterJdbcTemplate,
        mapper: ObjectMapper,
        environment: Environment,
    ): JdbcOutbox = JdbcOutbox(
        jdbc = jdbc,
        mapper = mapper,
        source = environment.getProperty("spring.application.name", "unknown-service"),
    )

    @Bean
    @ConditionalOnBean(KafkaTemplate::class, JdbcOutbox::class)
    @ConditionalOnMissingBean
    fun outboxPublisher(
        outbox: JdbcOutbox,
        kafka: KafkaTemplate<String, String>,
        properties: OutboxProperties,
        meters: MeterRegistry,
    ): OutboxPublisher = OutboxPublisher(outbox, kafka, properties, meters)
}
