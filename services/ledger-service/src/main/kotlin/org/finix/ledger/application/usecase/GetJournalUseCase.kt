package org.finix.ledger.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.domain.JournalEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Look up a journal entry by the originating transaction id. */
@Service
class GetJournalUseCase(
    private val ledgerRepository: LedgerRepository,
) {
    @Transactional(readOnly = true)
    operator fun invoke(transactionId: UUID): JournalEntry =
        ledgerRepository.findByTransactionId(transactionId)
            ?: throw DomainException(
                DomainError.NotFound(resource = "journal", identifier = transactionId.toString()),
            )
}
