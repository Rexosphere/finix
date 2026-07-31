package org.finix.ledger.domain

import java.time.Instant
import java.util.UUID

/**
 * A signed Merkle root over a contiguous window of [JournalEntry.entryHash] values.
 *
 * Signature is ML-DSA-65 over the canonical message
 * `merkleRoot || windowStartSeq || windowEndSeq || entryCount` (see application layer).
 */
data class LedgerAnchor(
    val id: UUID,
    val windowStartSeq: Long,
    val windowEndSeq: Long,
    val merkleRoot: String,
    val entryCount: Int,
    val signature: ByteArray,
    val publicKey: ByteArray,
    val anchoredAt: Instant,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is LedgerAnchor &&
                id == other.id &&
                windowStartSeq == other.windowStartSeq &&
                windowEndSeq == other.windowEndSeq &&
                merkleRoot == other.merkleRoot &&
                entryCount == other.entryCount &&
                signature.contentEquals(other.signature) &&
                publicKey.contentEquals(other.publicKey) &&
                anchoredAt == other.anchoredAt
            )

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + windowStartSeq.hashCode()
        result = 31 * result + windowEndSeq.hashCode()
        result = 31 * result + merkleRoot.hashCode()
        result = 31 * result + entryCount
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + anchoredAt.hashCode()
        return result
    }
}

/**
 * Full inclusion proof for a transaction: hash-chain coordinates plus RFC-6962 Merkle path
 * against the anchor that covers the entry's sequence.
 */
data class LedgerProof(
    val transactionId: UUID,
    val sequence: Long,
    val prevHash: String,
    val entryHash: String,
    val merkleRoot: String?,
    val merklePath: List<MerkleProofStep>,
    val leafIndex: Int?,
    val treeSize: Int?,
    val anchorId: UUID?,
    val anchorSignatureBase64: String?,
    val anchorPublicKeyBase64: String?,
    /** Human-readable summary kept for older clients. */
    val inclusion: String,
)

data class MerkleProofStep(
    val siblingHash: String,
    val isLeftSibling: Boolean,
)
