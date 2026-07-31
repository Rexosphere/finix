package org.finix.loan.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.loan.application.DecideLoanCommand
import org.finix.loan.application.port.LoanRepository
import org.finix.loan.domain.Loan
import org.finix.loan.domain.LoanCreditScoring
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ScoreAndDecideLoanUseCase(
    private val loans: LoanRepository,
    private val clock: Clock,
) {
    @Transactional
    fun execute(command: DecideLoanCommand): Loan {
        val loan = loans.findById(command.loanId)
            ?: DomainError.NotFound("Loan", command.loanId.toString()).raise()
        val score = LoanCreditScoring.score(loan.principal, command.riskHint)
        loan.decide(
            approved = LoanCreditScoring.isApproved(score),
            score = score,
            hint = command.riskHint,
            clock = clock,
        )
        return loans.save(loan)
    }
}
