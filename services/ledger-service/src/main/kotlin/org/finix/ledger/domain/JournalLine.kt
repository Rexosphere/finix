package org.finix.ledger.domain

import org.finix.kernel.domain.Money
import java.util.UUID

/**
 * One side of a double-entry posting: money moves into (CREDIT) or out of (DEBIT) an account
 * in the journal's currency.
 *
 * Amounts must be strictly positive — a zero or negative line is not a posting, it is a bug.
 */
data class JournalLine(
    val accountId: UUID,
    val side: EntrySide,
    val amount: Money,
) {
    init {
        require(amount.isPositive) { "Journal line amount must be positive, got $amount" }
    }
}
