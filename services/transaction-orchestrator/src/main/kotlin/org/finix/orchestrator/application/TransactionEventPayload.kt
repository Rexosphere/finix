package org.finix.orchestrator.application

import org.finix.kernel.messaging.EventEnvelope
import org.finix.kernel.messaging.Topics
import org.finix.orchestrator.application.port.OutboxPort
import org.finix.orchestrator.domain.TransferSaga

/** Payloads published on the transaction.* topics — keep fields stable for AsyncAPI consumers. */
data class TransactionEventPayload(
    val transferId: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amount: String,
    val state: String,
    val holdId: String,
    val failureReason: String? = null,
)

internal fun TransferSaga.toEventPayload(): TransactionEventPayload = TransactionEventPayload(
    transferId = id.toString(),
    fromAccountId = fromAccountId.toString(),
    toAccountId = toAccountId.toString(),
    amount = amount.toString(),
    state = state.name,
    holdId = holdId.toString(),
    failureReason = failureReason,
)

internal fun OutboxPort.appendTransactionEvent(topic: String, eventType: String, saga: TransferSaga) {
    append(
        topic = topic,
        envelope = EventEnvelope(
            eventType = eventType,
            aggregateType = "transaction",
            aggregateId = saga.id.toString(),
            payload = saga.toEventPayload(),
            source = "transaction-orchestrator",
        ),
    )
}

internal fun OutboxPort.appendInitiated(saga: TransferSaga) =
    appendTransactionEvent(Topics.TRANSACTION_INITIATED, Topics.TRANSACTION_INITIATED, saga)

internal fun OutboxPort.appendCommitted(saga: TransferSaga) =
    appendTransactionEvent(Topics.TRANSACTION_COMMITTED, Topics.TRANSACTION_COMMITTED, saga)

internal fun OutboxPort.appendFailed(saga: TransferSaga) =
    appendTransactionEvent(Topics.TRANSACTION_FAILED, Topics.TRANSACTION_FAILED, saga)
