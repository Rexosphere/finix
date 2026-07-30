package org.finix.identity.domain

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.domainRequire
import java.time.Instant
import java.util.UUID

/**
 * A browser or mobile install bound to a [UserProfile]. Trust decays and recovers through
 * [adjustTrust]; a revoked device can never authenticate again under the same row.
 */
data class Device(
    val id: UUID,
    val userId: UUID,
    val fingerprint: String,
    val platform: String,
    val trustScore: Int,
    val lastSeenAt: Instant,
    val revoked: Boolean,
) {
    init {
        domainRequire(trustScore in MIN_TRUST..MAX_TRUST) {
            DomainError.Invalid(
                detail = "trustScore must be between $MIN_TRUST and $MAX_TRUST",
                properties = mapOf("trustScore" to trustScore),
            )
        }
        domainRequire(fingerprint.isNotBlank()) {
            DomainError.Invalid("device fingerprint must not be blank")
        }
    }

    /** Bumps [lastSeenAt] on a successful authentication from this device. */
    fun recordLogin(at: Instant = Instant.now()): Device {
        domainRequire(!revoked) {
            DomainError.Conflict("revoked device cannot record a login", mapOf("deviceId" to id.toString()))
        }
        return copy(lastSeenAt = at)
    }

    /** Applies a signed trust delta and clamps to the valid range. */
    fun adjustTrust(delta: Int): Device {
        domainRequire(!revoked) {
            DomainError.Conflict("revoked device cannot change trust", mapOf("deviceId" to id.toString()))
        }
        return copy(trustScore = (trustScore + delta).coerceIn(MIN_TRUST, MAX_TRUST))
    }

    /** Soft-deletes the device; fingerprints remain for revoked-history risk scoring. */
    fun revoke(): Device {
        domainRequire(!revoked) {
            DomainError.Conflict("device is already revoked", mapOf("deviceId" to id.toString()))
        }
        return copy(revoked = true)
    }

    companion object {
        const val MIN_TRUST = 0
        const val MAX_TRUST = 100
        const val INITIAL_TRUST = 50
    }
}
