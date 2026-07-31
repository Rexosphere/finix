package org.finix.loan.adapter.`in`.rest

import org.finix.kernel.domain.Money
import org.finix.loan.domain.Loan
import org.finix.loan.domain.LoanStatus
import org.finix.loan.domain.RepaymentStatus
import java.util.UUID

data class ApplyLoanRequest(
    val borrowerUserId: UUID,
    val accountId: UUID,
    val principal: Money,
    val termMonths: Int = 12,
)

data class DecideLoanRequest(
    val riskHint: String? = null,
)

data class RepaymentItemResponse(
    val id: UUID,
    val installmentNumber: Int,
    val dueDate: String,
    val amount: Money,
    val status: RepaymentStatus,
)

data class LoanResponse(
    val id: UUID,
    val borrowerUserId: UUID,
    val accountId: UUID,
    val principal: Money,
    val termMonths: Int,
    val status: LoanStatus,
    val creditScore: Int?,
    val riskHint: String?,
    val appliedAt: String,
    val decidedAt: String?,
    val schedule: List<RepaymentItemResponse>,
)

fun Loan.toResponse(): LoanResponse = LoanResponse(
    id = id,
    borrowerUserId = borrowerUserId,
    accountId = accountId,
    principal = principal,
    termMonths = termMonths,
    status = status,
    creditScore = creditScore,
    riskHint = riskHint,
    appliedAt = appliedAt.toString(),
    decidedAt = decidedAt?.toString(),
    schedule = schedule.map {
        RepaymentItemResponse(
            id = it.id,
            installmentNumber = it.installmentNumber,
            dueDate = it.dueDate.toString(),
            amount = it.amount,
            status = it.status,
        )
    },
)
