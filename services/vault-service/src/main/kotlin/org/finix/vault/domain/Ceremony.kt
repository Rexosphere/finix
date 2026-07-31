package org.finix.vault.domain

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.domainRequire
import java.time.Instant
import java.util.UUID

/**
 * Master Key unlock ceremony aggregate: 3-of-5 custodian approvals gate enclave reconstruction.
 *
 * The aggregate never holds plaintext key material — only public Feldman commitments, sealed
 * shards (ciphertext), and after unlock the enclave egress log proving only network-config
 * plaintext left the boundary.
 */
class Ceremony(
    val id: UUID,
    state: CeremonyState,
    val threshold: Int = DEFAULT_THRESHOLD,
    val commitments: List<ByteArray>,
    val sealedNetworkConfig: ByteArray,
    approvals: Set<CustodianId> = emptySet(),
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var state: CeremonyState = state
        private set

    var updatedAt: Instant = updatedAt
        private set

    private val _approvals: MutableSet<CustodianId> = approvals.toMutableSet()

    val approvals: Set<CustodianId> get() = _approvals.toSet()

    val approvalCount: Int get() = _approvals.size

    /**
     * Record a custodian approval. Idempotent for the same custodian.
     * Transitions `PENDING/COLLECTING → COLLECTING`, and to `THRESHOLD_MET` when count ≥ threshold.
     */
    fun approve(custodianId: CustodianId, at: Instant) {
        domainRequire(state == CeremonyState.PENDING || state == CeremonyState.COLLECTING || state == CeremonyState.THRESHOLD_MET) {
            DomainError.Conflict(
                detail = "Ceremony $id cannot accept approvals in state $state",
                properties = mapOf("ceremonyId" to id.toString(), "state" to state.name),
            )
        }
        if (_approvals.add(custodianId)) {
            updatedAt = at
            when {
                _approvals.size >= threshold -> state = CeremonyState.THRESHOLD_MET
                state == CeremonyState.PENDING -> state = CeremonyState.COLLECTING
            }
        }
    }

    fun beginReconstruct(at: Instant) {
        domainRequire(state == CeremonyState.THRESHOLD_MET) {
            DomainError.Conflict(
                detail = "Ceremony $id requires THRESHOLD_MET before reconstruct (state=$state, approvals=$approvalCount/$threshold)",
                properties = mapOf(
                    "ceremonyId" to id.toString(),
                    "state" to state.name,
                    "approvals" to approvalCount,
                    "threshold" to threshold,
                ),
            )
        }
        state = CeremonyState.RECONSTRUCTING
        updatedAt = at
    }

    fun markUnlocked(at: Instant) {
        domainRequire(state == CeremonyState.RECONSTRUCTING) {
            DomainError.Conflict(
                detail = "Ceremony $id can only unlock from RECONSTRUCTING (state=$state)",
                properties = mapOf("ceremonyId" to id.toString(), "state" to state.name),
            )
        }
        state = CeremonyState.UNLOCKED
        updatedAt = at
    }

    fun markFailed(at: Instant) {
        domainRequire(state == CeremonyState.RECONSTRUCTING || state == CeremonyState.THRESHOLD_MET) {
            DomainError.Conflict(
                detail = "Ceremony $id cannot fail from state $state",
                properties = mapOf("ceremonyId" to id.toString(), "state" to state.name),
            )
        }
        state = CeremonyState.FAILED
        updatedAt = at
    }

    companion object {
        const val DEFAULT_THRESHOLD: Int = 3

        fun create(
            id: UUID = UUID.randomUUID(),
            commitments: List<ByteArray>,
            sealedNetworkConfig: ByteArray,
            threshold: Int = DEFAULT_THRESHOLD,
            at: Instant,
        ): Ceremony {
            domainRequire(commitments.isNotEmpty()) {
                DomainError.Invalid("Feldman commitments are required")
            }
            domainRequire(sealedNetworkConfig.isNotEmpty()) {
                DomainError.Invalid("Sealed network config is required")
            }
            domainRequire(threshold in 2..CustodianId.ALL.size) {
                DomainError.Invalid(
                    detail = "threshold must be in 2..${CustodianId.ALL.size}",
                    properties = mapOf("threshold" to threshold),
                )
            }
            return Ceremony(
                id = id,
                state = CeremonyState.PENDING,
                threshold = threshold,
                commitments = commitments.map { it.copyOf() },
                sealedNetworkConfig = sealedNetworkConfig.copyOf(),
                approvals = emptySet(),
                createdAt = at,
                updatedAt = at,
            )
        }

        fun rehydrate(
            id: UUID,
            state: CeremonyState,
            threshold: Int,
            commitments: List<ByteArray>,
            sealedNetworkConfig: ByteArray,
            approvals: Set<CustodianId>,
            createdAt: Instant,
            updatedAt: Instant,
        ): Ceremony = Ceremony(
            id = id,
            state = state,
            threshold = threshold,
            commitments = commitments.map { it.copyOf() },
            sealedNetworkConfig = sealedNetworkConfig.copyOf(),
            approvals = approvals,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}

/** A Master Key share sealed to the enclave public key (hybrid X25519 + ML-KEM-768). */
data class SealedShard(
    val id: UUID,
    val ceremonyId: UUID,
    val custodianId: CustodianId,
    /** Shamir evaluation point `x` (1..5). */
    val shareIndex: Int,
    val ciphertext: ByteArray,
    val createdAt: Instant,
) {
    init {
        require(shareIndex in 1..CustodianId.ALL.size) { "shareIndex out of range: $shareIndex" }
        require(ciphertext.isNotEmpty()) { "ciphertext must be non-empty" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SealedShard &&
                id == other.id &&
                ceremonyId == other.ceremonyId &&
                custodianId == other.custodianId &&
                shareIndex == other.shareIndex &&
                ciphertext.contentEquals(other.ciphertext) &&
                createdAt == other.createdAt
            )

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + ceremonyId.hashCode()
        result = 31 * result + custodianId.hashCode()
        result = 31 * result + shareIndex
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "SealedShard(id=$id, custodian=$custodianId, x=$shareIndex, ct=${ciphertext.size} bytes)"
}

/** One line of the enclave egress log — must never contain key material. */
data class EgressLogEntry(
    val id: UUID,
    val ceremonyId: UUID,
    val recordedAt: Instant,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "egress message must be non-blank" }
    }
}
