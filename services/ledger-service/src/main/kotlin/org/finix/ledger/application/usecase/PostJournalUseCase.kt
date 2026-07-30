package org.finix.ledger.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.ledger.application.LedgerCanonicalizer
import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.domain.JournalEntry
import org.finix.ledger.domain.JournalLine
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Append a balanced journal entry to the hash chain.
 *
 * Loads the current head under the transaction, hashes against that prevHash, and inserts.
 * A duplicate [transactionId] surfaces as [DomainError.Conflict] (unique constraint).
 */
@Service
class PostJournalUseCase(
    private val ledgerRepository: LedgerRepository,
    private val canonicalizer: LedgerCanonicalizer,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    operator fun invoke(transactionId: UUID, lines: List<JournalLine>): JournalEntry {
        val existing = ledgerRepository.findByTransactionId(transactionId)
        if (existing != null) {
            throw DomainException(
                DomainError.Conflict(
                    detail = "Journal for transaction $transactionId already exists",
                    properties = mapOf(
                        "transactionId" to transactionId.toString(),
                        "entryId" to existing.id.toString(),
                        "sequence" to existing.sequence,
                    ),
                ),
            )
        }

        // Validate before touching the head so unbalanced attempts do not contend on the tip.
        JournalEntry.requireBalanced(lines)

        val head = ledgerRepository.latestHead()
        val recordedAt = Instant.now(clock)
        val entry = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = transactionId,
            lines = lines,
            prevHash = head.latestHash,
            sequence = head.nextSequence(),
            recordedAt = recordedAt,
            canonicalize = canonicalizer::bytes,
        )

        try {
            ledgerRepository.append(entry)
        } catch (ex: DataIntegrityViolationException) {
            throw DomainException(
                DomainError.Conflict(
                    detail = "Journal for transaction $transactionId already exists",
                    properties = mapOf("transactionId" to transactionId.toString()),
                ),
                cause = ex,
            )
        }
        return entry
    }
}
