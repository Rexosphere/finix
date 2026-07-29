package org.finix.kernel.crypto

/**
 * A binary Merkle tree over ledger entry hashes, used to anchor a 60-second window of
 * transactions under a single signed root.
 *
 * Domain separation follows RFC 6962 (Certificate Transparency): leaves are hashed with a
 * `0x00` prefix and internal nodes with `0x01`. Without that prefix an attacker could present
 * an internal node as if it were a leaf and forge an inclusion proof for a transaction that
 * was never recorded.
 *
 * An odd node at any level is promoted unchanged rather than duplicated, which avoids the
 * CVE-2012-2459 duplicate-leaf malleability that lets two different leaf sets share a root.
 */
object MerkleTree {

    private val LEAF_PREFIX = byteArrayOf(0x00)
    private val NODE_PREFIX = byteArrayOf(0x01)

    /** One step of an inclusion proof: a sibling digest and which side it sits on. */
    data class ProofStep(val siblingHash: String, val isLeftSibling: Boolean)

    data class InclusionProof(
        val leafHash: String,
        val leafIndex: Int,
        val treeSize: Int,
        val root: String,
        val path: List<ProofStep>,
    )

    fun leafHash(entryHash: String): String =
        Hashing.sha256Hex(LEAF_PREFIX, Hashing.unhex(entryHash))

    fun nodeHash(left: String, right: String): String =
        Hashing.sha256Hex(NODE_PREFIX, Hashing.unhex(left), Hashing.unhex(right))

    /**
     * Root over [entryHashes] in ledger sequence order. An empty window has the all-zero root,
     * so an anchor can still be published for a quiet minute and the chain stays unbroken.
     */
    fun root(entryHashes: List<String>): String {
        if (entryHashes.isEmpty()) return Hashing.ZERO_DIGEST
        return levels(entryHashes).last().single()
    }

    fun proof(entryHashes: List<String>, leafIndex: Int): InclusionProof {
        require(leafIndex in entryHashes.indices) {
            "Leaf index $leafIndex outside window of ${entryHashes.size} entries"
        }
        val levels = levels(entryHashes)
        val path = mutableListOf<ProofStep>()
        var index = leafIndex

        for (level in levels.dropLast(1)) {
            val siblingIndex = if (index % 2 == 0) index + 1 else index - 1
            if (siblingIndex < level.size) {
                path += ProofStep(level[siblingIndex], isLeftSibling = siblingIndex < index)
            }
            index /= 2
        }

        return InclusionProof(
            leafHash = entryHashes[leafIndex],
            leafIndex = leafIndex,
            treeSize = entryHashes.size,
            root = levels.last().single(),
            path = path,
        )
    }

    /**
     * Recomputes the root from a proof. This is the whole point of the anchor: anyone holding
     * a receipt can verify inclusion without access to the ledger database.
     */
    fun verify(proof: InclusionProof): Boolean {
        var computed = leafHash(proof.leafHash)
        for (step in proof.path) {
            computed = if (step.isLeftSibling) {
                nodeHash(step.siblingHash, computed)
            } else {
                nodeHash(computed, step.siblingHash)
            }
        }
        return computed == proof.root
    }

    /** Level 0 is the hashed leaves; the last level is the single-element root. */
    private fun levels(entryHashes: List<String>): List<List<String>> {
        var current = entryHashes.map(::leafHash)
        val all = mutableListOf(current)
        while (current.size > 1) {
            current = current.chunked(2) { pair ->
                if (pair.size == 2) nodeHash(pair[0], pair[1]) else pair[0]
            }
            all += current
        }
        return all
    }
}
