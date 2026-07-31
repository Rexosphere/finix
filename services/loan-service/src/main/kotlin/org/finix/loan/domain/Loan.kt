package org.finix.loan.domain

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.domainRequire
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * SME micro-loan aggregate: application → credit decision → repayment schedule.
 *
 * The schedule is owned by the aggregate so decide/disburse stay one consistency boundary.
 */
class Loan(
    val id: UUID,
    val borrowerUserId: UUID,
    val accountId: UUID,
    val principal: Money,
    val termMonths: Int,
    status: LoanStatus,
    schedule: List<RepaymentScheduleItem>,
    creditScore: Int? = null,
    riskHint: String? = null,
    val appliedAt: Instant,
    decidedAt: Instant? = null,
    version: Long = 0,
) {
    var status: LoanStatus = status
        private set

    var creditScore: Int? = creditScore
        private set

    var riskHint: String? = riskHint
        private set

    var decidedAt: Instant? = decidedAt
        private set

    var version: Long = version
        private set

    private val _schedule: MutableList<RepaymentScheduleItem> = schedule.toMutableList()

    val schedule: List<RepaymentScheduleItem> get() = _schedule.toList()

    /**
     * Applies a credit decision. Idempotent when already APPROVED/REJECTED with the same outcome.
     */
    fun decide(approved: Boolean, score: Int, hint: String?, clock: Clock = Clock.systemUTC()) {
        domainRequire(score in LoanCreditScoring.MIN_SCORE..LoanCreditScoring.MAX_SCORE) {
            DomainError.Invalid(
                detail = "Credit score must be ${LoanCreditScoring.MIN_SCORE}..${LoanCreditScoring.MAX_SCORE}, got $score",
                properties = mapOf("score" to score),
            )
        }
        when (status) {
            LoanStatus.APPROVED -> {
                domainRequire(approved) {
                    DomainError.Conflict(
                        detail = "Loan '$id' is already APPROVED and cannot be rejected",
                        properties = mapOf("loanId" to id.toString(), "status" to status.name),
                    )
                }
                return
            }
            LoanStatus.REJECTED -> {
                domainRequire(!approved) {
                    DomainError.Conflict(
                        detail = "Loan '$id' is already REJECTED and cannot be approved",
                        properties = mapOf("loanId" to id.toString(), "status" to status.name),
                    )
                }
                return
            }
            LoanStatus.PENDING -> Unit
            else -> DomainError.Conflict(
                detail = "Loan '$id' cannot be decided from status $status",
                properties = mapOf("loanId" to id.toString(), "status" to status.name),
            ).raise()
        }
        creditScore = score
        riskHint = hint
        decidedAt = Instant.now(clock)
        status = if (approved) LoanStatus.APPROVED else LoanStatus.REJECTED
    }

    companion object {
        const val DEFAULT_TERM_MONTHS: Int = 12
        const val MAX_TERM_MONTHS: Int = 60

        fun apply(
            id: UUID = UUID.randomUUID(),
            borrowerUserId: UUID,
            accountId: UUID,
            principal: Money,
            termMonths: Int = DEFAULT_TERM_MONTHS,
            clock: Clock = Clock.systemUTC(),
        ): Loan {
            domainRequire(principal.isPositive) {
                DomainError.Invalid(
                    detail = "Loan principal must be positive, got $principal",
                    properties = mapOf("principal" to principal.toString()),
                )
            }
            domainRequire(termMonths in 1..MAX_TERM_MONTHS) {
                DomainError.Invalid(
                    detail = "Term months must be 1..$MAX_TERM_MONTHS, got $termMonths",
                    properties = mapOf("termMonths" to termMonths),
                )
            }
            val appliedAt = Instant.now(clock)
            val start = LocalDate.ofInstant(appliedAt, ZoneOffset.UTC)
            val parts = principal.allocate(termMonths)
            val schedule = parts.mapIndexed { index, amount ->
                RepaymentScheduleItem(
                    installmentNumber = index + 1,
                    dueDate = start.plusMonths((index + 1).toLong()),
                    amount = amount,
                )
            }
            return Loan(
                id = id,
                borrowerUserId = borrowerUserId,
                accountId = accountId,
                principal = principal,
                termMonths = termMonths,
                status = LoanStatus.PENDING,
                schedule = schedule,
                appliedAt = appliedAt,
            )
        }
    }
}
