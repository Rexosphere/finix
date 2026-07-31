package org.finix.ussd.application

import org.finix.kernel.domain.Money
import java.util.UUID

data class AccountBalanceView(
    val accountId: UUID,
    val accountNumber: String,
    val available: Money,
    val held: Money,
)

data class TransferResult(
    val sagaId: UUID,
    val status: String,
)
