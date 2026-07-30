package org.finix.identity.application.port

import org.finix.identity.domain.UserProfile
import java.util.UUID

/** Outbound port for [UserProfile] persistence. */
interface UserRepository {
    fun findById(id: UUID): UserProfile?
    fun findByKeycloakUserId(keycloakUserId: String): UserProfile?
    fun findByEmail(email: String): UserProfile?
    fun save(profile: UserProfile): UserProfile
}
