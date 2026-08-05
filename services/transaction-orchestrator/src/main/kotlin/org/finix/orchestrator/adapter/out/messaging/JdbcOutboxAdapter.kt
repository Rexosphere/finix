package org.finix.orchestrator.adapter.out.messaging

import org.finix.kernel.messaging.EventEnvelope
import org.finix.kernel.messaging.JdbcOutbox
import org.finix.orchestrator.application.port.OutboxPort
import org.springframework.stereotype.Component

/**
 * Binds [OutboxPort] to the shared-kernel transactional outbox.
 *
 * The port exists so the saga's application layer never sees JDBC; this adapter is the only
 * place that knows the events are relayed from a table rather than sent to Kafka directly.
 */
@Component
class JdbcOutboxAdapter(
    private val outbox: JdbcOutbox,
) : OutboxPort {

    override fun <T> append(topic: String, envelope: EventEnvelope<T>) = outbox.append(topic, envelope)
}
