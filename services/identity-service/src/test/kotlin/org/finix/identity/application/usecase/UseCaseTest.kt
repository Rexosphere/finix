package org.finix.identity.application.usecase

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.finix.identity.application.port.DeviceRepository
import org.finix.identity.application.port.UserRepository
import org.finix.identity.domain.Device
import org.finix.identity.domain.KycTier
import org.finix.identity.domain.RiskScore
import org.finix.identity.domain.UserProfile
import org.finix.kernel.domain.DomainException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UseCaseTest : StringSpec({

    val clock: Clock = Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC)
    val now = Instant.now(clock)

    fun profile(keycloakUserId: String = "kc-1") = UserProfile(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        keycloakUserId = keycloakUserId,
        email = "farmer@finix.lk",
        displayName = "Sunil Perera",
        nic = "198512345678",
        locale = "si",
        kycTier = KycTier.VERIFIED,
        createdAt = now,
    )

    fun device(
        userId: UUID = profile().id,
        fingerprint: String = "fp-1",
        trust: Int = 80,
        revoked: Boolean = false,
    ) = Device(
        id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        userId = userId,
        fingerprint = fingerprint,
        platform = "web",
        trustScore = trust,
        lastSeenAt = now,
        revoked = revoked,
    )

    "GetProfileUseCase returns the profile or NotFound" {
        val users = mockk<UserRepository>()
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { users.findByKeycloakUserId("missing") } returns null

        GetProfileUseCase(users).execute("kc-1").email shouldBe "farmer@finix.lk"
        shouldThrow<DomainException> { GetProfileUseCase(users).execute("missing") }
    }

    "RegisterDeviceUseCase creates a new device" {
        val users = mockk<UserRepository>()
        val devices = mockk<DeviceRepository>()
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { devices.findByUserIdAndFingerprint(profile().id, "fp-new") } returns null
        every { devices.save(any()) } answers { firstArg() }

        val created = RegisterDeviceUseCase(users, devices, clock)
            .execute("kc-1", "fp-new", "ios")

        created.fingerprint shouldBe "fp-new"
        created.trustScore shouldBe Device.INITIAL_TRUST
        created.platform shouldBe "ios"
        verify(exactly = 1) { devices.save(any()) }
    }

    "RegisterDeviceUseCase refreshes an existing active device" {
        val users = mockk<UserRepository>()
        val devices = mockk<DeviceRepository>()
        val existing = device(fingerprint = "fp-1")
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { devices.findByUserIdAndFingerprint(profile().id, "fp-1") } returns existing
        every { devices.save(any()) } answers { firstArg() }

        val updated = RegisterDeviceUseCase(users, devices, clock)
            .execute("kc-1", "fp-1", "web")

        updated.id shouldBe existing.id
        updated.lastSeenAt shouldBe now
    }

    "ListDevicesUseCase returns the user's devices" {
        val users = mockk<UserRepository>()
        val devices = mockk<DeviceRepository>()
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { devices.findByUserId(profile().id) } returns listOf(device())

        ListDevicesUseCase(users, devices).execute("kc-1").size shouldBe 1
    }

    "RevokeDeviceUseCase revokes an owned device" {
        val users = mockk<UserRepository>()
        val devices = mockk<DeviceRepository>()
        val owned = device()
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { devices.findById(owned.id) } returns owned
        every { devices.save(any()) } answers { firstArg() }

        RevokeDeviceUseCase(users, devices).execute("kc-1", owned.id).revoked shouldBe true
    }

    "RevokeDeviceUseCase refuses a device owned by someone else" {
        val users = mockk<UserRepository>()
        val devices = mockk<DeviceRepository>()
        val foreign = device(userId = UUID.randomUUID())
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { devices.findById(foreign.id) } returns foreign

        shouldThrow<DomainException> {
            RevokeDeviceUseCase(users, devices).execute("kc-1", foreign.id)
        }
    }

    "ScoreLoginRiskUseCase scores a new device at the step-up threshold" {
        val users = mockk<UserRepository>()
        val devices = mockk<DeviceRepository>()
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { devices.findByUserIdAndFingerprint(profile().id, "fp-new") } returns null
        every { devices.hasRevokedFingerprint(profile().id, "fp-new") } returns false

        val score = ScoreLoginRiskUseCase(users, devices).execute("kc-1", "fp-new", "127.0.0.1")
        score.score shouldBe RiskScore.NEW_DEVICE_PENALTY
        score.requireStepUp shouldBe true
    }

    "ScoreLoginRiskUseCase adds revoked-history penalty" {
        val users = mockk<UserRepository>()
        val devices = mockk<DeviceRepository>()
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { devices.findByUserIdAndFingerprint(profile().id, "fp-old") } returns null
        every { devices.hasRevokedFingerprint(profile().id, "fp-old") } returns true

        val score = ScoreLoginRiskUseCase(users, devices).execute("kc-1", "fp-old", "10.0.0.1")
        score.score shouldBe RiskScore.NEW_DEVICE_PENALTY + RiskScore.REVOKED_HISTORY_PENALTY
        score.requireStepUp shouldBe true
    }

    "ScoreLoginRiskUseCase uses inverse trust for known devices" {
        val users = mockk<UserRepository>()
        val devices = mockk<DeviceRepository>()
        every { users.findByKeycloakUserId("kc-1") } returns profile()
        every { devices.findByUserIdAndFingerprint(profile().id, "fp-1") } returns device(trust = 80)
        every { devices.hasRevokedFingerprint(profile().id, "fp-1") } returns false

        val score = ScoreLoginRiskUseCase(users, devices).execute("kc-1", "fp-1", "127.0.0.1")
        score.score shouldBe 20
        score.requireStepUp shouldBe false
    }

    "SeedIdentityUseCase creates all five personas idempotently" {
        val users = mockk<UserRepository>()
        every { users.findByKeycloakUserId(any()) } returns null
        every { users.findByEmail(any()) } returns null
        every { users.save(any()) } answers { firstArg() }

        val seeded = SeedIdentityUseCase(users, clock).execute()
        seeded.size shouldBe 5
        seeded.map { it.email }.toSet() shouldBe SeedIdentityUseCase.PERSONAS.map { it.email }.toSet()
        verify(exactly = 5) { users.save(any()) }

        // Second run: profiles already present by keycloak id — no further saves.
        every { users.findByKeycloakUserId(any()) } answers {
            val id = firstArg<String>()
            seeded.first { it.keycloakUserId == id }
        }
        SeedIdentityUseCase(users, clock).execute().size shouldBe 5
        verify(exactly = 5) { users.save(any()) }
    }
})
