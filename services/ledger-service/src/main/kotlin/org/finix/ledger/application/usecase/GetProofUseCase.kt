package org.finix.ledger.application.usecase

import org.finix.kernel.crypto.MerkleTree
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.ledger.application.port.AnchorRepository
import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.domain.LedgerProof
import org.finix.ledger.domain.MerkleProofStep
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Base64
import java.util.UUID

/**
 * Return hash-chain coordinates plus, when an anchor covers the entry, an RFC-6962 Merkle
 * inclusion proof and the ML-DSA signature over that window's root.
 */
@Service
class GetProofUseCase(
    private val ledgerRepository: LedgerRepository,
    private val anchorRepository: AnchorRepository,
) {
    @Transactional(readOnly = true)
    operator fun invoke(transactionId: UUID): LedgerProof {
        val entry = ledgerRepository.findByTransactionId(transactionId)
            ?: throw DomainException(
                DomainError.NotFound(resource = "journal", identifier = transactionId.toString()),
            )

        val anchor = anchorRepository.findCovering(entry.sequence)
        if (anchor == null) {
            return LedgerProof(
                transactionId = entry.transactionId,
                sequence = entry.sequence,
                prevHash = entry.prevHash,
                entryHash = entry.entryHash,
                merkleRoot = null,
                merklePath = emptyList(),
                leafIndex = null,
                treeSize = null,
                anchorId = null,
                anchorSignatureBase64 = null,
                anchorPublicKeyBase64 = null,
                inclusion = entry.entryHash,
            )
        }

        val window = ledgerRepository.findSequenceRange(anchor.windowStartSeq, anchor.windowEndSeq)
        val hashes = window.map { it.entryHash }
        val leafIndex = hashes.indexOf(entry.entryHash)
        if (leafIndex < 0) {
            DomainError.IntegrityViolation(
                invariant = "anchor-window",
                detail = "Entry ${entry.sequence} not found in covering anchor window",
            ).raise()
        }

        val merkle = MerkleTree.proof(hashes, leafIndex)
        return LedgerProof(
            transactionId = entry.transactionId,
            sequence = entry.sequence,
            prevHash = entry.prevHash,
            entryHash = entry.entryHash,
            merkleRoot = merkle.root,
            merklePath = merkle.path.map { MerkleProofStep(it.siblingHash, it.isLeftSibling) },
            leafIndex = merkle.leafIndex,
            treeSize = merkle.treeSize,
            anchorId = anchor.id,
            anchorSignatureBase64 = Base64.getEncoder().encodeToString(anchor.signature),
            anchorPublicKeyBase64 = Base64.getEncoder().encodeToString(anchor.publicKey),
            inclusion = merkle.root,
        )
    }
}
