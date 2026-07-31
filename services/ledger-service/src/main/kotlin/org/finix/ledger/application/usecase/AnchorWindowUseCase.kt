package org.finix.ledger.application.usecase

import org.finix.kernel.crypto.MerkleTree
import org.finix.kernel.crypto.PostQuantum
import org.finix.ledger.application.port.AnchorPort
import org.finix.ledger.application.port.AnchorRepository
import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.config.AnchorSigningKeys
import org.finix.ledger.domain.LedgerAnchor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Builds a Merkle root over every journal entry since the last anchor (or from sequence 1),
 * signs it with ML-DSA-65, and persists via [AnchorPort].
 */
@Service
class AnchorWindowUseCase(
    private val ledger: LedgerRepository,
    private val anchors: AnchorRepository,
    private val anchorPort: AnchorPort,
    private val signingKeys: AnchorSigningKeys,
    private val clock: Clock,
) {

    @Transactional
    fun execute(): LedgerAnchor? {
        val head = ledger.latestHead()
        if (head.latestSequence == 0L) return null

        val previous = anchors.findLatest()
        val fromSeq = (previous?.windowEndSeq ?: 0L) + 1L
        if (fromSeq > head.latestSequence) return null

        val entries = ledger.findSequenceRange(fromSeq, head.latestSequence)
        if (entries.isEmpty()) return null

        val hashes = entries.map { it.entryHash }
        val root = MerkleTree.root(hashes)
        val message = signingMessage(root, fromSeq, head.latestSequence, hashes.size)
        val signature = PostQuantum.sign(signingKeys.privateKey, message)

        val anchor = LedgerAnchor(
            id = UUID.randomUUID(),
            windowStartSeq = fromSeq,
            windowEndSeq = head.latestSequence,
            merkleRoot = root,
            entryCount = hashes.size,
            signature = signature,
            publicKey = signingKeys.publicKey.encoded,
            anchoredAt = Instant.now(clock),
        )
        val published = anchorPort.publish(anchor)
        return anchors.save(published)
    }

    companion object {
        fun signingMessage(root: String, start: Long, end: Long, count: Int): ByteArray =
            "$root|$start|$end|$count".toByteArray(StandardCharsets.UTF_8)
    }
}
