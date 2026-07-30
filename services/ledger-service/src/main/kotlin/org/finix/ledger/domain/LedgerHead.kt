package org.finix.ledger.domain

import org.finix.kernel.crypto.Hashing

/**
 * Tip of the append-only chain: the hash and sequence of the latest accepted journal entry.
 *
 * An empty ledger is represented by [GENESIS] — sequence 0 and [Hashing.ZERO_DIGEST] — so the
 * first real entry always chains from a well-known constant rather than a special-cased null.
 */
data class LedgerHead(
    val latestHash: String,
    val latestSequence: Long,
) {
    init {
        require(latestSequence >= 0) { "latestSequence must be non-negative, got $latestSequence" }
        require(latestHash.length == 64) { "latestHash must be 64 hex chars, got length ${latestHash.length}" }
    }

    /** Sequence number the next append must use. */
    fun nextSequence(): Long = latestSequence + 1

    companion object {
        val GENESIS: LedgerHead = LedgerHead(Hashing.ZERO_DIGEST, 0L)
    }
}
