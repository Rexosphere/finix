package org.finix.kernel.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A message durably queued in the same transaction as the state change that produced it. */
data class OutboxMessage(
    val id: UUID,
    val topic: String,
    val partitionKey: String,
    val eventType: String,
    val headers: Map<String, String>,
    val payloadJson: String,
    val createdAt: Instant,
    val attempts: Int = 0,
)

/**
 * The **transactional outbox** — the mechanism that makes "the ledger recorded it" and "the
 * event was published" a single atomic fact.
 *
 * Publishing to Kafka directly from a service method is a dual-write: the database commit and
 * the broker send are two independent operations, and a crash between them either loses an event
 * (a transfer that never notifies) or emits one for a rolled-back transaction (a notification for
 * money that was never moved). Neither is acceptable when the events drive fraud scoring and
 * customer SMS.
 *
 * Instead [append] writes a row inside the caller's existing transaction, and [OutboxPublisher]
 * relays it afterwards. The trade is at-least-once delivery, which is why every envelope carries
 * a deduplication id — see [EventEnvelope.eventId].
 *
 * ADR-0005 records why this is a poller rather than Debezium CDC: log-based capture needs a
 * Kafka Connect cluster and `wal_level=logical`, which is real Phase-3 infrastructure, and the
 * outbox *table* is identical either way — so the production evolution is a deployment change,
 * not a rewrite.
 */
class JdbcOutbox(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
    private val source: String,
) {

    /**
     * Enlists an event in the caller's transaction. Deliberately takes no transaction of its own:
     * if this ever ran in a separate transaction the atomicity guarantee would be gone.
     */
    fun <T> append(topic: String, envelope: EventEnvelope<T>) {
        val params = MapSqlParameterSource()
            .addValue("id", envelope.eventId)
            .addValue("topic", topic)
            .addValue("partitionKey", envelope.aggregateId)
            .addValue("eventType", envelope.eventType)
            .addValue("headers", mapper.writeValueAsString(envelope.headers() + (EventEnvelope.HEADER_SOURCE to source)))
            .addValue("payload", mapper.writeValueAsString(envelope))
            .addValue("createdAt", Timestamp.from(envelope.occurredAt))
        jdbc.update(INSERT_SQL, params)
    }

    /**
     * Claims up to [limit] unpublished messages for this publisher instance.
     *
     * `FOR UPDATE SKIP LOCKED` is what makes the poller horizontally scalable: several replicas
     * poll the same table and each takes a disjoint batch instead of serialising on the oldest
     * row. Ordering by `created_at, id` keeps per-aggregate order intact, since a single
     * aggregate's events are inserted in sequence.
     */
    fun claimBatch(limit: Int): List<OutboxMessage> =
        jdbc.query(CLAIM_SQL, MapSqlParameterSource("limit", limit)) { rs, _ -> mapRow(rs) }

    fun markPublished(ids: List<UUID>) {
        if (ids.isEmpty()) return
        jdbc.update(MARK_PUBLISHED_SQL, MapSqlParameterSource("ids", ids))
    }

    /**
     * Records a failed send. `attempts` is what a dead-letter alert fires on — a message stuck
     * above the threshold means the broker or the payload is broken, and silently retrying it
     * forever would hide that.
     */
    fun markFailed(id: UUID, reason: String) {
        jdbc.update(
            MARK_FAILED_SQL,
            MapSqlParameterSource().addValue("id", id).addValue("reason", reason.take(MAX_REASON_LENGTH)),
        )
    }

    /** Count of messages awaiting relay — exported as a gauge; sustained growth means the relay is stuck. */
    fun pendingCount(): Long =
        jdbc.queryForObject(PENDING_COUNT_SQL, MapSqlParameterSource(), Long::class.java) ?: 0L

    private fun mapRow(rs: ResultSet): OutboxMessage {
        @Suppress("UNCHECKED_CAST")
        val headers = mapper.readValue(rs.getString("headers"), Map::class.java) as Map<String, String>
        return OutboxMessage(
            id = rs.getObject("id", UUID::class.java),
            topic = rs.getString("topic"),
            partitionKey = rs.getString("partition_key"),
            eventType = rs.getString("event_type"),
            headers = headers,
            payloadJson = rs.getString("payload"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            attempts = rs.getInt("attempts"),
        )
    }

    private companion object {
        const val MAX_REASON_LENGTH = 500

        const val INSERT_SQL = """
            INSERT INTO outbox_message (id, topic, partition_key, event_type, headers, payload, created_at)
            VALUES (:id, :topic, :partitionKey, :eventType, cast(:headers AS jsonb), cast(:payload AS jsonb), :createdAt)
            ON CONFLICT (id) DO NOTHING
        """

        const val CLAIM_SQL = """
            SELECT id, topic, partition_key, event_type, headers, payload::text AS payload, created_at, attempts
            FROM outbox_message
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """

        const val MARK_PUBLISHED_SQL = """
            UPDATE outbox_message SET published_at = now(), last_error = NULL WHERE id IN (:ids)
        """

        const val MARK_FAILED_SQL = """
            UPDATE outbox_message SET attempts = attempts + 1, last_error = :reason WHERE id = :id
        """

        const val PENDING_COUNT_SQL = "SELECT count(*) FROM outbox_message WHERE published_at IS NULL"
    }
}
