package org.finix.account.application

import org.finix.kernel.domain.Money
import java.time.Instant
import java.util.UUID

data class ReconcileVoucherCommand(
    val deviceId: String,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: Money,
    val deviceSeq: Long,
    val nonce: String,
    val validUntil: Instant,
    val signatureBase64: String,
)
