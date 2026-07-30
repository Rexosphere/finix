package org.finix.ledger.domain

/**
 * Result of walking the hash chain from genesis to the tip.
 *
 * [valid] is true only when every link recomputes to its stored [org.finix.ledger.domain.JournalEntry.entryHash]
 * and every [org.finix.ledger.domain.JournalEntry.prevHash] matches the prior entry. The first
 * break is reported so operators can locate the tampered row without scanning the whole table by hand.
 */
data class VerificationReport(
    val valid: Boolean,
    val checkedEntries: Int,
    val firstBreakSequence: Long? = null,
    val detail: String? = null,
) {
    companion object {
        fun ok(checkedEntries: Int): VerificationReport =
            VerificationReport(valid = true, checkedEntries = checkedEntries)

        fun broken(checkedEntries: Int, firstBreakSequence: Long, detail: String): VerificationReport =
            VerificationReport(
                valid = false,
                checkedEntries = checkedEntries,
                firstBreakSequence = firstBreakSequence,
                detail = detail,
            )
    }
}
