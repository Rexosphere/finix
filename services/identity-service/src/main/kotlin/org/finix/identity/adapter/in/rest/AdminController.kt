package org.finix.identity.adapter.`in`.rest

import org.finix.identity.application.usecase.SeedIdentityUseCase
import org.finix.identity.domain.KycTier
import org.finix.identity.domain.UserProfile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin")
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
