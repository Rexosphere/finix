package org.finix.kernel.messaging

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Relays outbox rows to Kafka.
 *
 * The whole method runs in **one transaction** on purpose. `FOR UPDATE SKIP LOCKED` only excludes
 * other pollers for as long as the claiming transaction is open, so committing between the claim
 * and the mark would open a window in which a second replica republishes the same batch.
 *
 * Sends are awaited rather than fired and forgotten: a row is marked published only once the
 * broker has acknowledged it. A message that fails is left unpublished with its attempt count
 * incremented, so the next tick retries it and the ordering of the remaining backlog is intact.
 */
// Must be open: created via @Bean (not @Component), so kotlin-spring all-open does not
// apply, and @Transactional + @Scheduled need a CGLIB subclass.
open class OutboxPublisher(
    private val outbox: JdbcOutbox,
    private val kafka: KafkaTemplate<String, String>,
    private val properties: OutboxProperties,
    meters: MeterRegistry,
) {

    private val published = meters.counter("finix.outbox.published")
    private val failed = meters.counter("finix.outbox.failed")

    init {
        meters.gauge("finix.outbox.pending", this) { it.outbox.pendingCount().toDouble() }
    }

    @Scheduled(fixedDelayString = "\${finix.outbox.poll-interval-ms:500}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun relay() {
        val batch = outbox.claimBatch(properties.batchSize)
        if (batch.isEmpty()) return

        val sent = mutableListOf<UUID>()
        for (message in batch) {
            if (!send(message)) {
                // Stop at the first failure: continuing would deliver later events for the same
                // aggregate ahead of this one, and out-of-order money events are worse than slow ones.
                break
            }
            sent += message.id
        }

        outbox.markPublished(sent)
        published.increment(sent.size.toDouble())
        if (sent.size < batch.size) {
            log.warn { "Outbox relay published ${sent.size} of ${batch.size}; retrying the remainder next tick" }
        }
    }

    private fun send(message: OutboxMessage): Boolean = try {
        val record = ProducerRecord(message.topic, null, message.partitionKey, message.payloadJson)
        message.headers.forEach { (key, value) ->
            record.headers().add(RecordHeader(key, value.toByteArray(StandardCharsets.UTF_8)))
        }
        kafka.send(record).get(properties.sendTimeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (ex: java.util.concurrent.ExecutionException) {
        recordFailure(message, ex)
        false
    } catch (ex: java.util.concurrent.TimeoutException) {
        recordFailure(message, ex)
        false
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        recordFailure(message, ex)
        false
    }

    private fun recordFailure(message: OutboxMessage, ex: Exception) {
        failed.increment()
        val reason = "${ex.javaClass.simpleName}: ${ex.message}"
        outbox.markFailed(message.id, reason)
        if (message.attempts + 1 >= properties.alertAfterAttempts) {
            log.error(ex) { "Outbox message ${message.id} (${message.eventType}) has failed ${message.attempts + 1} times" }
        } else {
            log.warn { "Outbox message ${message.id} (${message.eventType}) failed: $reason" }
        }
    }
}
