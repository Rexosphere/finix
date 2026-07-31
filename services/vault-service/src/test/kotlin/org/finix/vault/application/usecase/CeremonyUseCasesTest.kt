package org.finix.vault.application.usecase

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.vault.application.AttestationDoc
import org.finix.vault.application.ReconstructResult
import org.finix.vault.application.port.CeremonyRepository
import org.finix.vault.application.port.EnclaveClient
import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.CeremonyState
import org.finix.vault.domain.CustodianId
import org.finix.vault.domain.EgressLogEntry
import org.finix.vault.domain.SealedShard
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CeremonyUseCasesTest : StringSpec({

    val fixedInstant = Instant.parse("2026-07-30T12:00:00Z")
    val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    fun ceremonyAt(state: CeremonyState, approvals: Set<CustodianId> = emptySet()): Ceremony =
        Ceremony.rehydrate(
            id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            state = state,
            threshold = 3,
            commitments = listOf(byteArrayOf(1)),
            sealedNetworkConfig = byteArrayOf(2),
            approvals = approvals,
            createdAt = fixedInstant,
            updatedAt = fixedInstant,
        )

    "ApproveCeremonyUseCase records approval and reaches THRESHOLD_MET at 3" {
        val repo = mockk<CeremonyRepository>()
        val ceremony = ceremonyAt(CeremonyState.PENDING)
        every { repo.findLatest() } returns ceremony
        every { repo.save(any()) } answers { firstArg() }

        val useCase = ApproveCeremonyUseCase(repo, clock)
        useCase.execute(CustodianId.CENTRAL_BANK).approvalCount shouldBe 1
        useCase.execute(CustodianId.GOVT_DR).state shouldBe CeremonyState.COLLECTING
        val met = useCase.execute(CustodianId.IEEE_VAULT)
        met.state shouldBe CeremonyState.THRESHOLD_MET
        met.approvalCount shouldBe 3
        verify(atLeast = 3) { repo.save(any()) }
    }

    "ApproveCeremonyUseCase raises NotFound when no ceremony" {
        val repo = mockk<CeremonyRepository>()
        every { repo.findLatest() } returns null
        val ex = shouldThrow<DomainException> {
            ApproveCeremonyUseCase(repo, clock).execute(CustodianId.CENTRAL_BANK)
        }
        (ex.error is DomainError.NotFound) shouldBe true
    }

    "GetCeremonyStatusUseCase returns shard count" {
        val repo = mockk<CeremonyRepository>()
        val ceremony = ceremonyAt(CeremonyState.COLLECTING, setOf(CustodianId.CENTRAL_BANK))
        every { repo.findLatest() } returns ceremony
        every { repo.findShards(ceremony.id) } returns listOf(
            SealedShard(UUID.randomUUID(), ceremony.id, CustodianId.CENTRAL_BANK, 1, byteArrayOf(1), fixedInstant),
        )
        val status = GetCeremonyStatusUseCase(repo).execute()
        status.shardCount shouldBe 1
        status.ceremony.approvalCount shouldBe 1
        status.custodians.size shouldBe 5
    }

    "ReconstructMasterKeyUseCase verifies attestation first and unlocks" {
        val repo = mockk<CeremonyRepository>()
        val enclave = mockk<EnclaveClient>()
        val ceremony = ceremonyAt(
            CeremonyState.THRESHOLD_MET,
            setOf(CustodianId.CENTRAL_BANK, CustodianId.GOVT_DR, CustodianId.IEEE_VAULT),
        )
        every { repo.findLatest() } returns ceremony
        every { repo.save(any()) } answers { firstArg() }
        every { repo.findShards(ceremony.id) } returns (1..5).map { i ->
            SealedShard(
                id = UUID.randomUUID(),
                ceremonyId = ceremony.id,
                custodianId = CustodianId.ALL[i - 1],
                shareIndex = i,
                ciphertext = byteArrayOf(i.toByte()),
                createdAt = fixedInstant,
            )
        }
        every { enclave.attest() } returns AttestationDoc(
            moduleId = "test",
            timestamp = fixedInstant.epochSecond,
            digest = "abc",
            signature = byteArrayOf(1),
            valid = true,
        )
        every {
            enclave.reconstruct(any(), any(), any())
        } returns ReconstructResult(
            networkConfigPlaintext = """{"network":"finix-core"}""",
            egressLog = listOf("egress: network-config only"),
        )
        val egress = slot<EgressLogEntry>()
        every { repo.appendEgress(capture(egress)) } answers { egress.captured }

        val result = ReconstructMasterKeyUseCase(repo, enclave, clock).execute()
        result.networkConfigPlaintext shouldBe """{"network":"finix-core"}"""
        ceremony.state shouldBe CeremonyState.UNLOCKED
        verify(ordering = io.mockk.Ordering.ORDERED) {
            enclave.attest()
            enclave.reconstruct(any(), any(), any())
        }
        verify(atLeast = 1) { repo.appendEgress(any()) }
    }

    "ReconstructMasterKeyUseCase fails closed on bad attestation" {
        val repo = mockk<CeremonyRepository>()
        val enclave = mockk<EnclaveClient>()
        val ceremony = ceremonyAt(
            CeremonyState.THRESHOLD_MET,
            setOf(CustodianId.CENTRAL_BANK, CustodianId.GOVT_DR, CustodianId.IEEE_VAULT),
        )
        every { repo.findLatest() } returns ceremony
        every { repo.save(any()) } answers { firstArg() }
        every { enclave.attest() } returns AttestationDoc(
            moduleId = "bad",
            timestamp = 0,
            digest = "x",
            signature = byteArrayOf(),
            valid = false,
        )

        val ex = shouldThrow<DomainException> {
            ReconstructMasterKeyUseCase(repo, enclave, clock).execute()
        }
        (ex.error is DomainError.IntegrityViolation) shouldBe true
        ceremony.state shouldBe CeremonyState.FAILED
        verify(exactly = 0) { enclave.reconstruct(any(), any(), any()) }
    }

    "ReconstructMasterKeyUseCase refuses before threshold" {
        val repo = mockk<CeremonyRepository>()
        val enclave = mockk<EnclaveClient>()
        every { repo.findLatest() } returns ceremonyAt(CeremonyState.COLLECTING, setOf(CustodianId.CENTRAL_BANK))
        val ex = shouldThrow<DomainException> {
            ReconstructMasterKeyUseCase(repo, enclave, clock).execute()
        }
        (ex.error is DomainError.Conflict) shouldBe true
        verify(exactly = 0) { enclave.attest() }
    }

    "SplitRecoverySharesUseCase is 2-of-3" {
        val secret = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val useCase = SplitRecoverySharesUseCase()
        val shares = useCase.execute(secret)
        shares.size shouldBe 3
        useCase.reconstruct(listOf(shares[0], shares[1])).contentEquals(secret) shouldBe true
    }
})
