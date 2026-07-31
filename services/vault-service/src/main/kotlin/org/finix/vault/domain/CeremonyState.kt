package org.finix.vault.domain

/**
 * Lifecycle of a Master Key unlock ceremony.
 *
 * ```
 * PENDING → COLLECTING → THRESHOLD_MET → RECONSTRUCTING → UNLOCKED
 *                                              ↘ FAILED
 * ```
 */
enum class CeremonyState {
    /** Split exists; no custodian has approved yet. */
    PENDING,

    /** At least one approval recorded, fewer than [Ceremony.threshold]. */
    COLLECTING,

    /** ≥ threshold approvals; reconstruct may be triggered. */
    THRESHOLD_MET,

    /** Enclave attestation + reconstruct in flight. */
    RECONSTRUCTING,

    /** Network config released; Master Key never left the enclave. */
    UNLOCKED,

    /** Attestation or reconstruct failed; ceremony is terminal until re-seeded. */
    FAILED,
}
