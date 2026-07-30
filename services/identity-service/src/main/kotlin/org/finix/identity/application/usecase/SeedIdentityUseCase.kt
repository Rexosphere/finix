package org.finix.identity.application.usecase

import org.finix.identity.application.port.UserRepository
import org.finix.identity.domain.KycTier
import org.finix.identity.domain.UserProfile
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Idempotently creates the five Keycloak demo personas as local [UserProfile] rows.
 *
 * Keycloak subject ids are not fixed in the realm export, so we derive a stable
 * [UserProfile.keycloakUserId] with [UUID.nameUUIDFromBytes] over the persona email. The web
 * demo and seed scripts use the same derivation until a real IdP sync lands.
 */
@Service
class SeedIdentityUseCase(
    private val users: UserRepository,
    private val clock: Clock,
) {
    fun execute(): List<UserProfile> = PERSONAS.map { persona ->
        val keycloakUserId = stableKeycloakId(persona.email)
        users.findByKeycloakUserId(keycloakUserId)
            ?: users.findByEmail(persona.email)
            ?: users.save(
                UserProfile(
                    id = UUID.randomUUID(),
                    keycloakUserId = keycloakUserId,
                    email = persona.email,
                    displayName = persona.displayName,
                    nic = persona.nic,
                    locale = persona.locale,
                    kycTier = persona.kycTier,
                    createdAt = Instant.now(clock),
                ),
            )
    }

    data class PersonaSeed(
        val email: String,
        val displayName: String,
        val nic: String?,
        val locale: String,
        val kycTier: KycTier,
    )

    companion object {
        val PERSONAS: List<PersonaSeed> = listOf(
            PersonaSeed("farmer@finix.lk", "Sunil Perera", "198512345678", "si", KycTier.VERIFIED),
            PersonaSeed("sme@finix.lk", "Nimali Fernando", "199012345679", "en", KycTier.ENHANCED),
            PersonaSeed("elder@finix.lk", "Kamala Silva", "195012345680", "si", KycTier.BASIC),
            PersonaSeed("regulator@finix.lk", "CBSL Auditor", null, "en", KycTier.NONE),
            PersonaSeed("admin@finix.lk", "Ops Admin", null, "en", KycTier.NONE),
        )

        fun stableKeycloakId(email: String): String =
            UUID.nameUUIDFromBytes("finix:persona:$email".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
