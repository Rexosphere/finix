package org.finix.identity.adapter.`in`.rest

import org.finix.identity.application.usecase.SeedIdentityUseCase
import org.finix.identity.domain.KycTier
import org.finix.identity.domain.UserProfile
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Demo-data seeding, and nothing else.
 *
 * This writes the five Keycloak demo personas into the identity database. The shared security
 * configuration lists `POST /api/v1/admin/seed` as `permitAll()`, so wherever this bean exists
 * it is an unauthenticated write endpoint.
 *
 * `@Profile("dev")` — not `("dev", "default")` — is the point: a service booted with no profile
 * selected, which is how production runs, never registers this bean, so the route returns 404
 * instead of depending on an authorisation rule. [SeedIdentityUseCase] is untouched and stays
 * callable from tests and from a dev boot.
 *
 * Nothing that is not demo seeding belongs on this class — anything added here inherits the
 * profile gate and would silently disappear in production.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Profile("dev")
class AdminController(
    private val seedIdentity: SeedIdentityUseCase,
) {
    @PostMapping("/seed")
    fun seed(): SeedResponse {
        val profiles = seedIdentity.execute()
        return SeedResponse(profiles = profiles.map(SeededProfileResponse::from))
    }
}

data class SeedResponse(
    val profiles: List<SeededProfileResponse>,
)

data class SeededProfileResponse(
    val id: UUID,
    val keycloakUserId: String,
    val email: String,
    val displayName: String,
    val nic: String?,
    val locale: String,
    val kycTier: KycTier,
    val createdAt: Instant,
) {
    companion object {
        fun from(profile: UserProfile) = SeededProfileResponse(
            id = profile.id,
            keycloakUserId = profile.keycloakUserId,
            email = profile.email,
            displayName = profile.displayName,
            nic = profile.nic,
            locale = profile.locale,
            kycTier = profile.kycTier,
            createdAt = profile.createdAt,
        )
    }
}
