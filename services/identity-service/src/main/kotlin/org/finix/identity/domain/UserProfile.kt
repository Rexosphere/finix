package org.finix.identity.domain

import java.time.Instant
import java.util.UUID

/**
 * Local projection of a Keycloak subject. Credentials never leave the IdP; this aggregate
 * holds the banking-facing attributes (NIC, locale, KYC) that product services ask for.
 */
data class UserProfile(
    val id: UUID,
    val keycloakUserId: String,
    val email: String,
    val displayName: String,
    val nic: String?,
    val locale: String,
    val kycTier: KycTier,
    val createdAt: Instant,
)
