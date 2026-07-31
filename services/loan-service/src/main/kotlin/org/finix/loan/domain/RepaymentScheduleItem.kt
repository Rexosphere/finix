package org.finix.loan.domain

import org.finix.kernel.domain.Money
import java.time.LocalDate
import java.util.UUID

/** One equal installment in a loan repayment schedule. */
data class RepaymentScheduleItem(
    val id: UUID = UUID.randomUUID(),
    val installmentNumber: Int,
    val dueDate: LocalDate,
    val amount: Money,
    val status: RepaymentStatus = RepaymentStatus.DUE,
)
