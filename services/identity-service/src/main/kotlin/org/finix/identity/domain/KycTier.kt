package org.finix.identity.domain

/**
 * Progressive KYC assurance. Product eligibility (transfers, loans, USSD limits) keys off
 * this tier rather than inventing per-product flags.
 */
enum class KycTier {
    /** Registered but identity documents have not been collected. */
    NONE,

    /** NIC captured; basic retail products allowed. */
    BASIC,

    /** NIC + simulated biometric verified. */
    VERIFIED,

    /** Enhanced diligence (SME / high-value). */
    ENHANCED,
}
