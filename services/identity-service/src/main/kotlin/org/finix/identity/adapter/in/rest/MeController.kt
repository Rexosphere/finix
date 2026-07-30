package org.finix.identity.adapter.`in`.rest

import org.finix.identity.application.usecase.GetProfileUseCase
import org.finix.identity.application.usecase.ListDevicesUseCase
import org.finix.identity.application.usecase.RegisterDeviceUseCase
import org.finix.identity.application.usecase.RevokeDeviceUseCase
import org.finix.identity.domain.Device
import org.finix.identity.domain.KycTier
import org.finix.identity.domain.UserProfile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val getProfile: GetProfileUseCase,
    private val listDevices: ListDevicesUseCase,
    private val registerDevice: RegisterDeviceUseCase,
    private val revokeDevice: RevokeDeviceUseCase,
) {
    @GetMapping
    fun profile(
        @RequestHeader(name = CurrentUser.DEMO_USER_HEADER, required = false) demoUser: String?,
    ): ProfileResponse {
        val profile = getProfile.execute(CurrentUser.keycloakUserId(demoUser))
        return ProfileResponse.from(profile)
    }

    @GetMapping("/devices")
    fun devices(
        @RequestHeader(name = CurrentUser.DEMO_USER_HEADER, required = false) demoUser: String?,
    ): List<DeviceResponse> =
        listDevices.execute(CurrentUser.keycloakUserId(demoUser)).map(DeviceResponse::from)

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @RequestHeader(name = CurrentUser.DEMO_USER_HEADER, required = false) demoUser: String?,
        @RequestBody body: RegisterDeviceRequest,
    ): DeviceResponse {
        val device = registerDevice.execute(
            keycloakUserId = CurrentUser.keycloakUserId(demoUser),
            fingerprint = body.fingerprint,
            platform = body.platform,
        )
        return DeviceResponse.from(device)
    }

    @DeleteMapping("/devices/{id}")
    fun revoke(
        @RequestHeader(name = CurrentUser.DEMO_USER_HEADER, required = false) demoUser: String?,
        @PathVariable id: UUID,
    ): DeviceResponse {
        val device = revokeDevice.execute(CurrentUser.keycloakUserId(demoUser), id)
        return DeviceResponse.from(device)
    }
}

data class RegisterDeviceRequest(
    val fingerprint: String,
    val platform: String,
)

data class ProfileResponse(
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
        fun from(profile: UserProfile) = ProfileResponse(
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

data class DeviceResponse(
    val id: UUID,
    val fingerprint: String,
    val platform: String,
    val trustScore: Int,
    val lastSeenAt: Instant,
    val revoked: Boolean,
) {
    companion object {
        fun from(device: Device) = DeviceResponse(
            id = device.id,
            fingerprint = device.fingerprint,
            platform = device.platform,
            trustScore = device.trustScore,
            lastSeenAt = device.lastSeenAt,
            revoked = device.revoked,
        )
    }
}
