package org.finix.account.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import java.time.Instant
import java.util.UUID

class OfflineDeviceTest : StringSpec({

    val now = Instant.parse("2026-07-31T12:00:00Z")
    val policy = OfflinePolicy(
        maxPerTxMinor = 50_000,
        maxCumulativeMinor = 100_000,
    )

    fun device(
        seq: Long = 0,
        cumulative: Long = 0,
        quarantined: Boolean = false,
    ) = OfflineDevice(
        deviceId = "dev-1",
        ownerUserId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        publicKeySpki = byteArrayOf(1),
        lastDeviceSeq = seq,
        cumulativeMinor = cumulative,
        quarantined = quarantined,
        quarantineReason = if (quarantined) "prior" else null,
    )

    "assertAccepts happy path then recordSettlement" {
        val d = device()
        d.assertAccepts(1, "100.00".lkr(), policy, now, now.plusSeconds(60))
        d.recordSettlement(1, "100.00".lkr())
        d.lastDeviceSeq shouldBe 1
        d.cumulativeMinor shouldBe 10_000
    }

    "rejects quarantined device" {
        shouldThrow<DomainException> {
            device(quarantined = true).assertAccepts(1, "10.00".lkr(), policy, now, now.plusSeconds(60))
        }
    }

    "rejects expired voucher" {
        shouldThrow<DomainException> {
            device().assertAccepts(1, "10.00".lkr(), policy, now, now.minusSeconds(1))
        }
    }

    "rejects non-increasing device seq" {
        shouldThrow<DomainException> {
            device(seq = 5).assertAccepts(5, "10.00".lkr(), policy, now, now.plusSeconds(60))
        }
    }

    "rejects per-transaction limit" {
        shouldThrow<DomainException> {
            device().assertAccepts(1, "600.00".lkr(), policy, now, now.plusSeconds(60))
        }
    }

    "rejects cumulative limit" {
        shouldThrow<DomainException> {
            device(cumulative = 95_000).assertAccepts(1, "100.00".lkr(), policy, now, now.plusSeconds(60))
        }
    }

    "quarantine flips flags" {
        val d = device()
        d.quarantine("nonce-reuse")
        d.quarantined shouldBe true
        d.quarantineReason shouldBe "nonce-reuse"
    }
})
