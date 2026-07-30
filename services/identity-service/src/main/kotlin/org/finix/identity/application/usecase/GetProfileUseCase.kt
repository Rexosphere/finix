package org.finix.identity.application.usecase

import org.finix.identity.application.port.UserRepository
import org.finix.identity.domain.UserProfile
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service

/** Returns the caller's [UserProfile] keyed by Keycloak subject (`sub`). */
@Service
class GetProfileUseCase(
    private val users: UserRepository,
) {
    fun execute(keycloakUserId: String): UserProfile =
        users.findByKeycloakUserId(keycloakUserId)
            ?: DomainError.NotFound("UserProfile", keycloakUserId).raise()
}
