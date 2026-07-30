package org.finix.ledger.domain

import java.util.UUID

/**
 * M2 inclusion stub: the entry hashes that prove a transaction sits on the chain.
 *
 * Full RFC-6962 Merkle proofs land in M5; until then, returning the stored prev/entry digests
 * is enough for a judge to recompute [org.finix.kernel.crypto.Hashing.chain] against the payload.
 */
data class LedgerProof(
    val transactionId: UUID,
    val sequence: Long,
    val prevHash: String,
    val entryHash: String,
    val inclusion: String,
)
