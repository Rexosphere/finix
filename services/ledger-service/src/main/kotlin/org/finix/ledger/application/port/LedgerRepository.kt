package org.finix.ledger.application.port

import org.finix.ledger.domain.JournalEntry
import org.finix.ledger.domain.LedgerHead
import org.finix.ledger.domain.VerificationReport
import java.util.UUID

/**
 * Persistence port for the append-only journal.
 *
 * Implementations must never UPDATE or DELETE ledger rows — the database triggers refuse it,
 * and the adapter must not attempt it either. The sole exception is the **dev-profile** tamper
 * helper, which goes through `finix_dev_tamper_entry_hash` for the immutability demo.
 */
interface LedgerRepository {

    fun append(entry: JournalEntry)

    fun latestHead(): LedgerHead

    fun findByTransactionId(transactionId: UUID): JournalEntry?

    fun findFromSequence(fromSequence: Long, limit: Int): List<JournalEntry>

    /** Entries in [fromSequence, toSequence] inclusive, ordered by sequence. */
    fun findSequenceRange(fromSequence: Long, toSequence: Long): List<JournalEntry>

    fun verifyChain(): VerificationReport

    /**
     * DEV ONLY: flip one hex digit of entry_hash at [sequence] via the privileged SQL function.
     */
    fun injectTamper(sequence: Long)
}
