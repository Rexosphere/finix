package org.finix.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.finix.kernel.domain.DomainException
import java.time.Instant
import java.util.UUID

class DeviceTest : StringSpec({

    fun device(
        trust: Int = Device.INITIAL_TRUST,
        revoked: Boolean = false,
        seen: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ) = Device(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        fingerprint = "fp-abc",
        platform = "web",
        trustScore = trust,
        lastSeenAt = seen,
        revoked = revoked,
    )

    "recordLogin bumps lastSeenAt" {
        val before = Instant.parse("2026-01-01T00:00:00Z")
        val after = Instant.parse("2026-01-02T12:00:00Z")
        device(seen = before).recordLogin(after).lastSeenAt shouldBe after
    }

    "adjustTrust clamps to 0..100" {
        device(trust = 95).adjustTrust(20).trustScore shouldBe Device.MAX_TRUST
        device(trust = 5).adjustTrust(-20).trustScore shouldBe Device.MIN_TRUST
    }

    "revoke is idempotent-refusing" {
        val revoked = device().revoke()
        revoked.revoked shouldBe true
        shouldThrow<DomainException> { revoked.revoke() }
    }

    "revoked device cannot record login or adjust trust" {
        val revoked = device().revoke()
        shouldThrow<DomainException> { revoked.recordLogin() }
        shouldThrow<DomainException> { revoked.adjustTrust(1) }
    }

    "constructor rejects out-of-range trust" {
        shouldThrow<DomainException> { device(trust = 101) }
        shouldThrow<DomainException> { device(trust = -1) }
    }
})
