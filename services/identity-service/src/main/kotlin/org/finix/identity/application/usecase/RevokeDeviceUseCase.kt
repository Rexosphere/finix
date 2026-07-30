package org.finix.identity.application.usecase

import org.finix.identity.application.port.DeviceRepository
import org.finix.identity.application.port.UserRepository
import org.finix.identity.domain.Device
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service
import java.util.UUID

/** Revokes a device owned by the caller ("sign out this device"). */
@Service
class RevokeDeviceUseCase(
    private val users: UserRepository,
    private val devices: DeviceRepository,
) {
    fun execute(keycloakUserId: String, deviceId: UUID): Device {
        val profile = users.findByKeycloakUserId(keycloakUserId)
            ?: DomainError.NotFound("UserProfile", keycloakUserId).raise()

        val device = devices.findById(deviceId)
            ?: DomainError.NotFound("Device", deviceId.toString()).raise()

        if (device.userId != profile.id) {
            DomainError.Forbidden(
                detail = "device does not belong to the caller",
                properties = mapOf("deviceId" to deviceId.toString()),
            ).raise()
        }

        return devices.save(device.revoke())
    }
}
