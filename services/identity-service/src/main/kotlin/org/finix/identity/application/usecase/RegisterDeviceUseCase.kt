package org.finix.identity.application.usecase

import org.finix.identity.application.port.DeviceRepository
import org.finix.identity.application.port.UserRepository
import org.finix.identity.domain.Device
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Registers a device fingerprint for the caller, or refreshes last-seen if it already exists. */
@Service
class RegisterDeviceUseCase(
    private val users: UserRepository,
    private val devices: DeviceRepository,
    private val clock: Clock,
) {
    fun execute(keycloakUserId: String, fingerprint: String, platform: String): Device {
        val profile = users.findByKeycloakUserId(keycloakUserId)
            ?: DomainError.NotFound("UserProfile", keycloakUserId).raise()

        val existing = devices.findByUserIdAndFingerprint(profile.id, fingerprint)
        if (existing != null && !existing.revoked) {
            return devices.save(existing.recordLogin(Instant.now(clock)))
        }

        val device = Device(
            id = UUID.randomUUID(),
            userId = profile.id,
            fingerprint = fingerprint,
            platform = platform,
            trustScore = Device.INITIAL_TRUST,
            lastSeenAt = Instant.now(clock),
            revoked = false,
        )
        return devices.save(device)
    }
}
