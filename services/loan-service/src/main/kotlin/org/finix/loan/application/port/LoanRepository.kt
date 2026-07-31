package org.finix.loan.application.port

import org.finix.loan.domain.Loan
import java.util.UUID

/** Persistence port for the [Loan] aggregate. */
interface LoanRepository {
    fun save(loan: Loan): Loan
    fun findById(id: UUID): Loan?
    fun findByBorrower(borrowerUserId: UUID): List<Loan>
    fun findAll(): List<Loan>
}
