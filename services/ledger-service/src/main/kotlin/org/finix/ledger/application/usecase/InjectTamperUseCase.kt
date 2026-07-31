package org.finix.ledger.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.ledger.application.port.LedgerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InjectTamperUseCase(
    private val ledger: LedgerRepository,
) {
    @Transactional
    fun execute(sequence: Long) {
        if (sequence < 1L) {
            DomainError.Invalid("sequence must be >= 1").raise()
        }
        ledger.injectTamper(sequence)
    }
}
