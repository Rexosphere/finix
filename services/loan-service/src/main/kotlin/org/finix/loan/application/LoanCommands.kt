package org.finix.loan.application

import org.finix.kernel.domain.Money
import java.util.UUID

data class ApplyLoanCommand(
    val borrowerUserId: UUID,
    val accountId: UUID,
    val principal: Money,
    val termMonths: Int = 12,
)

data class DecideLoanCommand(
    val loanId: UUID,
    val riskHint: String? = null,
)
