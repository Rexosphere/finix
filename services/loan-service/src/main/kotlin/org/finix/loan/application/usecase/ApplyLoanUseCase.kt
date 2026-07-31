package org.finix.loan.application.usecase

import org.finix.loan.application.ApplyLoanCommand
import org.finix.loan.application.port.LoanRepository
import org.finix.loan.domain.Loan
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ApplyLoanUseCase(
    private val loans: LoanRepository,
    private val clock: Clock,
) {
    @Transactional
    fun execute(command: ApplyLoanCommand): Loan {
        val loan = Loan.apply(
            borrowerUserId = command.borrowerUserId,
            accountId = command.accountId,
            principal = command.principal,
            termMonths = command.termMonths,
            clock = clock,
        )
        return loans.save(loan)
    }
}
