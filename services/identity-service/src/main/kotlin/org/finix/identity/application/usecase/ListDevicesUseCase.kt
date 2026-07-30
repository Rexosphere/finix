package org.finix.identity.application.usecase

import org.finix.identity.application.port.DeviceRepository
import org.finix.identity.application.port.UserRepository
import org.finix.identity.domain.Device
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service

/** Lists every device (including revoked) bound to the caller's profile. */
@Service
class ListDevicesUseCase(
    private val users: UserRepository,
    private val devices: DeviceRepository,
) {
    fun execute(keycloakUserId: String): List<Device> {
        val profile = users.findByKeycloakUserId(keycloakUserId)
            ?: DomainError.NotFound("UserProfile", keycloakUserId).raise()
        return devices.findByUserId(profile.id)
    }
}
