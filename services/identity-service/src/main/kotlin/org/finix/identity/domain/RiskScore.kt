package org.finix.identity.domain

/**
 * Deterministic login-risk outcome consumed by the Keycloak adaptive authenticator (and the
 * BFF). [requireStepUp] is true when [score] meets or exceeds [STEP_UP_THRESHOLD].
 */
data class RiskScore(
    val score: Int,
    val requireStepUp: Boolean,
) {
    init {
        require(score in Device.MIN_TRUST..Device.MAX_TRUST) {
            "risk score must be between ${Device.MIN_TRUST} and ${Device.MAX_TRUST}"
        }
    }

    companion object {
        const val STEP_UP_THRESHOLD = 40
        const val NEW_DEVICE_PENALTY = 40
        const val REVOKED_HISTORY_PENALTY = 30

        fun of(score: Int): RiskScore {
            val clamped = score.coerceIn(Device.MIN_TRUST, Device.MAX_TRUST)
            return RiskScore(score = clamped, requireStepUp = clamped >= STEP_UP_THRESHOLD)
        }
    }
}
