package org.finix.account.adapter.out.messaging

import org.finix.account.application.port.OfflineEventPublisher
import org.finix.account.domain.OfflineVoucher
import org.finix.kernel.messaging.EventEnvelope
import org.finix.kernel.messaging.JdbcOutbox
import org.finix.kernel.messaging.Topics
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Publishes offline reconciliation outcomes through the shared-kernel transactional outbox.
 *
 * The outbox (rather than a direct `KafkaTemplate.send`) is what keeps "the voucher settled"
 * and "risk-ai was told" a single atomic fact: both the balance write and the event row commit
 * in the use case's transaction, or neither does.
 */
@Component
class OutboxOfflineEventPublisher(
    private val outbox: JdbcOutbox,
) : OfflineEventPublisher {

    override fun publishSettled(voucher: OfflineVoucher) {
        outbox.append(
            topic = Topics.OFFLINE_SYNC,
            envelope = EventEnvelope(
                eventType = "offline.voucher_settled",
                aggregateType = "OfflineVoucher",
                aggregateId = voucher.id.toString(),
                payload = OfflineVoucherSettledPayload(
                    voucherId = voucher.id,
                    deviceId = voucher.deviceId,
                    payerAccountId = voucher.payerAccountId,
                    payeeAccountId = voucher.payeeAccountId,
                    amount = voucher.amount.toString(),
                    deviceSeq = voucher.deviceSeq,
                    settledAt = voucher.createdAt,
                ),
            ),
        )
    }

    /**
     * Anomalies are keyed by device rather than voucher: risk-ai correlates repeated
     * double-spend attempts from one device, and partitioning by device keeps them ordered.
     */
    override fun publishAnomaly(deviceId: String, reason: String, deviceSeq: Long?, nonce: String?) {
        outbox.append(
            topic = Topics.RISK_OFFLINE_ANOMALY,
            envelope = EventEnvelope(
                eventType = "risk.offline_anomaly",
                aggregateType = "OfflineDevice",
                aggregateId = deviceId,
                payload = OfflineAnomalyPayload(
                    deviceId = deviceId,
                    reason = reason,
                    deviceSeq = deviceSeq,
                    nonce = nonce,
                ),
            ),
        )
    }
}

data class OfflineVoucherSettledPayload(
    val voucherId: UUID,
    val deviceId: String,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: String,
    val deviceSeq: Long,
    val settledAt: Instant,
)

data class OfflineAnomalyPayload(
    val deviceId: String,
    val reason: String,
    val deviceSeq: Long?,
    val nonce: String?,
)
