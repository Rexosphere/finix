package org.finix.vault.application

import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.CustodianId

/** Snapshot of ceremony progress for the admin UI. */
data class CeremonyStatus(
    val ceremony: Ceremony,
    val shardCount: Int,
    val custodians: List<CustodianId>,
)

/**
 * Mock Nitro-format attestation document returned by the enclave before any shard is released.
 */
data class AttestationDoc(
    val moduleId: String,
    val timestamp: Long,
    val digest: String,
    val signature: ByteArray,
    val valid: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is AttestationDoc &&
                moduleId == other.moduleId &&
                timestamp == other.timestamp &&
                digest == other.digest &&
                signature.contentEquals(other.signature) &&
                valid == other.valid
            )

    override fun hashCode(): Int {
        var result = moduleId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + digest.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + valid.hashCode()
        return result
    }
}

/**
 * Result of enclave-side reconstruction: network-config plaintext only — never the Master Key.
 */
data class ReconstructResult(
    val networkConfigPlaintext: String,
    val egressLog: List<String>,
)
