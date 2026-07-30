package org.finix.kernel.messaging

import com.fasterxml.jackson.annotation.JsonInclude
import org.finix.kernel.web.CorrelationContext
import java.time.Instant
import java.util.UUID

/**
 * The envelope every FINIX Kafka message is wrapped in — the wire contract described by
 * `docs/api/asyncapi.yaml`.
 *
 * A bare payload on a topic is unusable at scale, so the metadata here is not ceremony:
 *
 *  - [eventId] is the **consumer's deduplication key**. The outbox publishes at-least-once, so
 *    every consumer will eventually see a duplicate; without a stable id, "idempotent consumer"
 *    is an aspiration rather than an implementation.
 *  - [correlationId] and [traceId] carry the originating HTTP request across the async gap, so a
 *    trace does not end at the producer.
 *  - [schemaVersion] lets a payload evolve without a topic rename; consumers branch on it.
 *  - [aggregateId] doubles as the Kafka partition key, which is what guarantees that two events
 *    about the same account are processed in order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventEnvelope<T>(
    /** Event type in `<aggregate>.<past-tense-verb>` form, e.g. `transaction.initiated`. */
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val payload: T,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
    val schemaVersion: Int = 1,
    val correlationId: String? = CorrelationContext.correlationId(),
    val traceId: String? = CorrelationContext.traceId(),
    /** Producing service name, so a misbehaving publisher is identifiable from the message alone. */
    val source: String? = null,
) {
    /** Kafka headers a consumer can filter on without deserialising the body. */
    fun headers(): Map<String, String> = buildMap {
        put(HEADER_EVENT_ID, eventId.toString())
        put(HEADER_EVENT_TYPE, eventType)
        put(HEADER_SCHEMA_VERSION, schemaVersion.toString())
        correlationId?.let { put(HEADER_CORRELATION_ID, it) }
        traceId?.let { put(HEADER_TRACE_ID, it) }
        source?.let { put(HEADER_SOURCE, it) }
    }

    companion object {
        const val HEADER_EVENT_ID: String = "finix-event-id"
        const val HEADER_EVENT_TYPE: String = "finix-event-type"
        const val HEADER_SCHEMA_VERSION: String = "finix-schema-version"
        const val HEADER_CORRELATION_ID: String = "finix-correlation-id"
        const val HEADER_TRACE_ID: String = "finix-trace-id"
        const val HEADER_SOURCE: String = "finix-source"
    }
}

/**
 * The Kafka topics of the FINIX event plane, named exactly as the Phase-1 blueprint specified.
 *
 * They are constants rather than free-form strings so that a typo is a compile error instead of
 * a message that vanishes into an auto-created topic nobody consumes.
 */
object Topics {
    const val TRANSACTION_INITIATED: String = "transaction.initiated"
    const val TRANSACTION_COMMITTED: String = "transaction.committed"
    const val TRANSACTION_FAILED: String = "transaction.failed"
    const val OFFLINE_SYNC: String = "offline.sync"
    const val AUDIT_ANCHOR: String = "audit.anchor"
    const val RISK_DECISION: String = "risk.decision"
    const val RISK_OFFLINE_ANOMALY: String = "risk.offline_anomaly"
    const val NOTIFICATION_REQUESTED: String = "notification.requested"
}
