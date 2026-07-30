package org.finix.ledger.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.domain.LedgerProof
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Return the hash-chain coordinates for a transaction.
 *
 * M5 will expand [LedgerProof.inclusion] into a full Merkle inclusion proof; for M2 the
 * entry hash itself is the stub so clients can still recompute the chain link.
 */
@Service
class GetProofUseCase(
    private val ledgerRepository: LedgerRepository,
) {
    @Transactional(readOnly = true)
    operator fun invoke(transactionId: UUID): LedgerProof {
        val entry = ledgerRepository.findByTransactionId(transactionId)
            ?: throw DomainException(
                DomainError.NotFound(resource = "journal", identifier = transactionId.toString()),
            )
        return LedgerProof(
            transactionId = entry.transactionId,
            sequence = entry.sequence,
            prevHash = entry.prevHash,
            entryHash = entry.entryHash,
            inclusion = entry.entryHash,
        )
    }
}
