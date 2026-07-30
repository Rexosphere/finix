package org.finix.identity.application.usecase

import org.finix.identity.application.port.DeviceRepository
import org.finix.identity.application.port.UserRepository
import org.finix.identity.domain.Device
import org.finix.identity.domain.RiskScore
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service

/**
 * Deterministic login-risk scorer used before MFA step-up decisions.
 *
 * Rules:
 *  - unknown fingerprint → [RiskScore.NEW_DEVICE_PENALTY]
 *  - fingerprint previously revoked → add [RiskScore.REVOKED_HISTORY_PENALTY]
 *  - otherwise → inverse of the device trust score (`100 - trust`)
 *
 * [ip] is part of the Keycloak SPI contract; IP-reputation enrichment is deferred to risk-ai.
 */
@Service
class ScoreLoginRiskUseCase(
    private val users: UserRepository,
    private val devices: DeviceRepository,
) {
    @Suppress("UNUSED_PARAMETER")
    fun execute(keycloakUserId: String, fingerprint: String, ip: String): RiskScore {
        val profile = users.findByKeycloakUserId(keycloakUserId)
            ?: DomainError.NotFound("UserProfile", keycloakUserId).raise()

        val device = devices.findByUserIdAndFingerprint(profile.id, fingerprint)
            ?.takeUnless { it.revoked }
        val revokedHistory = devices.hasRevokedFingerprint(profile.id, fingerprint)

        val score = when {
            device == null -> {
                var s = RiskScore.NEW_DEVICE_PENALTY
                if (revokedHistory) {
                    s += RiskScore.REVOKED_HISTORY_PENALTY
                }
                s
            }
            else -> Device.MAX_TRUST - device.trustScore
        }

        return RiskScore.of(score)
    }
}
