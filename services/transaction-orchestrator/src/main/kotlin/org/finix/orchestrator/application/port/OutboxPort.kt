package org.finix.orchestrator.application.port

import org.finix.kernel.messaging.EventEnvelope

/** Port over the transactional outbox so use cases stay free of JDBC. */
interface OutboxPort {
    fun <T> append(topic: String, envelope: EventEnvelope<T>)
}
