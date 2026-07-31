package org.finix.loan.application.usecase

import org.finix.loan.application.port.LoanRepository
import org.finix.loan.domain.Loan
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ListLoansUseCase(
    private val loans: LoanRepository,
) {
    @Transactional(readOnly = true)
    fun execute(borrowerUserId: UUID?): List<Loan> =
        if (borrowerUserId == null) loans.findAll() else loans.findByBorrower(borrowerUserId)
}
