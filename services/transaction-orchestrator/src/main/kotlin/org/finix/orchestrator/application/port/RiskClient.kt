package org.finix.orchestrator.application.port

import org.finix.orchestrator.application.RiskAssessment

interface RiskClient {
    fun scoreTransfer(
        transactionId: String,
        fromAccountId: String,
        toAccountId: String,
        amountMinor: Long,
        currency: String,
        velocity1h: Int = 0,
        newDevice: Boolean = false,
        offlineVoucher: Boolean = false,
    ): RiskAssessment
}
