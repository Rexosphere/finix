package org.finix.ledger.application.port

import org.finix.ledger.domain.LedgerAnchor

/**
 * Publishes a Merkle root for a ledger window. Default adapter signs locally with ML-DSA-65;
 * Fabric adapter (compose `--profile fabric`) is the Phase-3 / optional path.
 */
interface AnchorPort {
    fun publish(anchor: LedgerAnchor): LedgerAnchor
}

interface AnchorRepository {
    fun save(anchor: LedgerAnchor): LedgerAnchor
    fun findLatest(): LedgerAnchor?
    fun findCovering(sequence: Long): LedgerAnchor?
    fun findAll(): List<LedgerAnchor>
}
