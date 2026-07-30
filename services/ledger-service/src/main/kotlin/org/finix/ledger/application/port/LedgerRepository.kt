package org.finix.ledger.application.port

import org.finix.ledger.domain.JournalEntry
import org.finix.ledger.domain.LedgerHead
import org.finix.ledger.domain.VerificationReport
import java.util.UUID

/**
 * Persistence port for the append-only journal.
 *
 * Implementations must never UPDATE or DELETE ledger rows — the database triggers refuse it,
 * and the adapter must not attempt it either.
 */
interface LedgerRepository {

    /** Append a new entry (header + lines) in one transaction. */
    fun append(entry: JournalEntry)

    /** Tip of the chain, or [LedgerHead.GENESIS] when the ledger is empty. */
    fun latestHead(): LedgerHead

    fun findByTransactionId(transactionId: UUID): JournalEntry?

    /** Entries with sequence >= [fromSequence], ordered by sequence ascending, capped at [limit]. */
    fun findFromSequence(fromSequence: Long, limit: Int): List<JournalEntry>

    /** Full-chain walk from sequence 1; recomputes hashes against stored payloads. */
    fun verifyChain(): VerificationReport
}
