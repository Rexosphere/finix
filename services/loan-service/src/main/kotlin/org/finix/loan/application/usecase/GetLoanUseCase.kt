package org.finix.loan.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.loan.application.port.LoanRepository
import org.finix.loan.domain.Loan
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class GetLoanUseCase(
    private val loans: LoanRepository,
) {
    @Transactional(readOnly = true)
    fun execute(loanId: UUID): Loan =
        loans.findById(loanId)
            ?: DomainError.NotFound("Loan", loanId.toString()).raise()
}
