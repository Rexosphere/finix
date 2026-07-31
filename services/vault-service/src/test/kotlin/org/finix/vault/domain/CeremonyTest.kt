package org.finix.vault.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.finix.kernel.domain.DomainException
import java.time.Instant
import java.util.UUID

class CeremonyTest : StringSpec({

    fun fresh(): Ceremony = Ceremony.create(
        id = UUID.randomUUID(),
        commitments = listOf(byteArrayOf(1, 2, 3)),
        sealedNetworkConfig = byteArrayOf(9, 9, 9),
        at = Instant.parse("2026-01-01T00:00:00Z"),
    )

    "approvals progress to THRESHOLD_MET at 3" {
        val ceremony = fresh()
        ceremony.state shouldBe CeremonyState.PENDING
        ceremony.approve(CustodianId.CENTRAL_BANK, Instant.parse("2026-01-01T00:01:00Z"))
        ceremony.state shouldBe CeremonyState.COLLECTING
        ceremony.approve(CustodianId.GOVT_DR, Instant.parse("2026-01-01T00:02:00Z"))
        ceremony.state shouldBe CeremonyState.COLLECTING
        ceremony.approvalCount shouldBe 2
        ceremony.approve(CustodianId.IEEE_VAULT, Instant.parse("2026-01-01T00:03:00Z"))
        ceremony.state shouldBe CeremonyState.THRESHOLD_MET
        ceremony.approvalCount shouldBe 3
    }

    "duplicate approval is idempotent" {
        val ceremony = fresh()
        ceremony.approve(CustodianId.CENTRAL_BANK, Instant.parse("2026-01-01T00:01:00Z"))
        ceremony.approve(CustodianId.CENTRAL_BANK, Instant.parse("2026-01-01T00:02:00Z"))
        ceremony.approvalCount shouldBe 1
        ceremony.state shouldBe CeremonyState.COLLECTING
    }

    "extra approvals after threshold stay THRESHOLD_MET" {
        val ceremony = fresh()
        CustodianId.ALL.take(3).forEach {
            ceremony.approve(it, Instant.parse("2026-01-01T00:05:00Z"))
        }
        ceremony.state shouldBe CeremonyState.THRESHOLD_MET
        ceremony.approve(CustodianId.CLOUD_HSM_A, Instant.parse("2026-01-01T00:06:00Z"))
        ceremony.state shouldBe CeremonyState.THRESHOLD_MET
        ceremony.approvalCount shouldBe 4
    }

    "reconstruct requires THRESHOLD_MET" {
        val ceremony = fresh()
        shouldThrow<DomainException> {
            ceremony.beginReconstruct(Instant.parse("2026-01-01T00:04:00Z"))
        }
    }

    "unlock path PENDING→…→UNLOCKED" {
        val ceremony = fresh()
        CustodianId.ALL.take(3).forEach {
            ceremony.approve(it, Instant.parse("2026-01-01T00:05:00Z"))
        }
        ceremony.beginReconstruct(Instant.parse("2026-01-01T00:06:00Z"))
        ceremony.state shouldBe CeremonyState.RECONSTRUCTING
        ceremony.markUnlocked(Instant.parse("2026-01-01T00:07:00Z"))
        ceremony.state shouldBe CeremonyState.UNLOCKED
    }

    "markFailed from THRESHOLD_MET and RECONSTRUCTING" {
        val a = fresh()
        CustodianId.ALL.take(3).forEach { a.approve(it, Instant.parse("2026-01-01T00:05:00Z")) }
        a.markFailed(Instant.parse("2026-01-01T00:06:00Z"))
        a.state shouldBe CeremonyState.FAILED

        val b = fresh()
        CustodianId.ALL.take(3).forEach { b.approve(it, Instant.parse("2026-01-01T00:05:00Z")) }
        b.beginReconstruct(Instant.parse("2026-01-01T00:06:00Z"))
        b.markFailed(Instant.parse("2026-01-01T00:07:00Z"))
        b.state shouldBe CeremonyState.FAILED
    }

    "approvals / unlock / fail rejected in terminal states" {
        val unlocked = fresh()
        CustodianId.ALL.take(3).forEach { unlocked.approve(it, Instant.parse("2026-01-01T00:05:00Z")) }
        unlocked.beginReconstruct(Instant.parse("2026-01-01T00:06:00Z"))
        unlocked.markUnlocked(Instant.parse("2026-01-01T00:07:00Z"))
        shouldThrow<DomainException> {
            unlocked.approve(CustodianId.CLOUD_HSM_B, Instant.parse("2026-01-01T00:08:00Z"))
        }
        shouldThrow<DomainException> {
            unlocked.markFailed(Instant.parse("2026-01-01T00:09:00Z"))
        }
        shouldThrow<DomainException> {
            unlocked.markUnlocked(Instant.parse("2026-01-01T00:10:00Z"))
        }
    }

    "create validates commitments, sealed config, and threshold" {
        shouldThrow<DomainException> {
            Ceremony.create(
                commitments = emptyList(),
                sealedNetworkConfig = byteArrayOf(1),
                at = Instant.parse("2026-01-01T00:00:00Z"),
            )
        }
        shouldThrow<DomainException> {
            Ceremony.create(
                commitments = listOf(byteArrayOf(1)),
                sealedNetworkConfig = byteArrayOf(),
                at = Instant.parse("2026-01-01T00:00:00Z"),
            )
        }
        shouldThrow<DomainException> {
            Ceremony.create(
                commitments = listOf(byteArrayOf(1)),
                sealedNetworkConfig = byteArrayOf(1),
                threshold = 1,
                at = Instant.parse("2026-01-01T00:00:00Z"),
            )
        }
    }

    "rehydrate restores approvals and state" {
        val id = UUID.randomUUID()
        val ceremony = Ceremony.rehydrate(
            id = id,
            state = CeremonyState.COLLECTING,
            threshold = 3,
            commitments = listOf(byteArrayOf(7)),
            sealedNetworkConfig = byteArrayOf(8),
            approvals = setOf(CustodianId.CENTRAL_BANK),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:01:00Z"),
        )
        ceremony.id shouldBe id
        ceremony.approvalCount shouldBe 1
        ceremony.state shouldBe CeremonyState.COLLECTING
    }

    "exactly five custodians exist and parse is case-insensitive" {
        CustodianId.ALL.size shouldBe 5
        CustodianId.entries.map { it.name } shouldBe listOf(
            "CENTRAL_BANK", "GOVT_DR", "IEEE_VAULT", "CLOUD_HSM_A", "CLOUD_HSM_B",
        )
        CustodianId.parse("central_bank") shouldBe CustodianId.CENTRAL_BANK
        shouldThrow<DomainException> { CustodianId.parse("nobody") }
    }

    "SealedShard and EgressLogEntry equality / validation" {
        val id = UUID.randomUUID()
        val ceremonyId = UUID.randomUUID()
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val a = SealedShard(id, ceremonyId, CustodianId.CENTRAL_BANK, 1, byteArrayOf(1, 2), at)
        val b = SealedShard(id, ceremonyId, CustodianId.CENTRAL_BANK, 1, byteArrayOf(1, 2), at)
        val c = SealedShard(id, ceremonyId, CustodianId.CENTRAL_BANK, 1, byteArrayOf(9), at)
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a shouldNotBe c
        a.toString().contains("CENTRAL_BANK") shouldBe true
        shouldThrow<IllegalArgumentException> {
            SealedShard(id, ceremonyId, CustodianId.CENTRAL_BANK, 0, byteArrayOf(1), at)
        }
        shouldThrow<IllegalArgumentException> {
            SealedShard(id, ceremonyId, CustodianId.CENTRAL_BANK, 1, byteArrayOf(), at)
        }
        val egress = EgressLogEntry(id, ceremonyId, at, "ok")
        egress.message shouldBe "ok"
        shouldThrow<IllegalArgumentException> {
            EgressLogEntry(id, ceremonyId, at, "  ")
        }
    }
})
