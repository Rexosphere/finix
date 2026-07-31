package org.finix.vault.application.usecase

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.finix.kernel.crypto.PostQuantum
import org.finix.vault.application.AttestationDoc
import org.finix.vault.application.HybridSeal
import org.finix.vault.application.port.CeremonyRepository
import org.finix.vault.application.port.EnclaveKeyPort
import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.CeremonyState
import org.finix.vault.domain.CustodianId
import org.finix.vault.domain.EgressLogEntry
import org.finix.vault.domain.SealedShard
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SplitAndSeedUseCaseTest : StringSpec({

    val fixedInstant = Instant.parse("2026-07-30T12:00:00Z")
    val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    "SplitMasterKeyUseCase seals five shards to enclave keys" {
        val kem = PostQuantum.generateKemKeyPair()
        val x25519 = HybridSeal.generateX25519KeyPair()
        val keys = object : EnclaveKeyPort {
            override fun kemPublicKeyEncoded(): ByteArray = kem.public.encoded
            override fun x25519PublicKeyEncoded(): ByteArray = x25519.public.encoded
        }
        val repo = mockk<CeremonyRepository>()
        every { repo.save(any()) } answers { firstArg() }
        every { repo.replaceShards(any(), any()) } returns Unit

        val ceremony = SplitMasterKeyUseCase(
            ceremonies = repo,
            enclaveKeys = keys,
            clock = clock,
            random = SecureRandom(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
        ).execute(networkConfigPlaintext = """{"network":"demo"}""")

        ceremony.state shouldBe CeremonyState.PENDING
        ceremony.commitments.isNotEmpty() shouldBe true
        ceremony.sealedNetworkConfig.isNotEmpty() shouldBe true
        verify(exactly = 1) { repo.save(any()) }
        verify(exactly = 1) {
            repo.replaceShards(ceremony.id, match { it.size == 5 && it.map { s -> s.custodianId }.toSet() == CustodianId.ALL.toSet() })
        }
    }

    "StartCeremonyUseCase returns existing complete ceremony" {
        val repo = mockk<CeremonyRepository>()
        val split = mockk<SplitMasterKeyUseCase>()
        val ceremony = Ceremony.create(
            commitments = listOf(byteArrayOf(1)),
            sealedNetworkConfig = byteArrayOf(2),
            at = fixedInstant,
        )
        every { repo.findLatest() } returns ceremony
        every { repo.findShards(ceremony.id) } returns (1..5).map { i ->
            SealedShard(UUID.randomUUID(), ceremony.id, CustodianId.ALL[i - 1], i, byteArrayOf(i.toByte()), fixedInstant)
        }
        StartCeremonyUseCase(repo, split).execute() shouldBe ceremony
        verify(exactly = 0) { split.execute() }
    }

    "StartCeremonyUseCase splits when shards missing" {
        val repo = mockk<CeremonyRepository>()
        val split = mockk<SplitMasterKeyUseCase>()
        val fresh = Ceremony.create(
            commitments = listOf(byteArrayOf(1)),
            sealedNetworkConfig = byteArrayOf(2),
            at = fixedInstant,
        )
        every { repo.findLatest() } returns null
        every { split.execute(any(), any()) } returns fresh
        StartCeremonyUseCase(repo, split).execute() shouldBe fresh
        verify(exactly = 1) { split.execute(any(), any()) }
    }

    "SeedVaultUseCase skips when shards already present" {
        val repo = mockk<CeremonyRepository>()
        val split = mockk<SplitMasterKeyUseCase>()
        val ceremony = Ceremony.create(
            commitments = listOf(byteArrayOf(1)),
            sealedNetworkConfig = byteArrayOf(2),
            at = fixedInstant,
        )
        every { repo.findLatest() } returns ceremony
        every { repo.findShards(ceremony.id) } returns listOf(
            SealedShard(UUID.randomUUID(), ceremony.id, CustodianId.CENTRAL_BANK, 1, byteArrayOf(1), fixedInstant),
        )
        SeedVaultUseCase(repo, split).execute(force = false) shouldBe ceremony
        verify(exactly = 0) { repo.deleteAll() }
        verify(exactly = 0) { split.execute() }
    }

    "SeedVaultUseCase force recreates" {
        val repo = mockk<CeremonyRepository>()
        val split = mockk<SplitMasterKeyUseCase>()
        val fresh = Ceremony.create(
            commitments = listOf(byteArrayOf(9)),
            sealedNetworkConfig = byteArrayOf(8),
            at = fixedInstant,
        )
        every { repo.findLatest() } returns fresh
        every { repo.findShards(any()) } returns listOf(
            SealedShard(UUID.randomUUID(), fresh.id, CustodianId.CENTRAL_BANK, 1, byteArrayOf(1), fixedInstant),
        )
        every { repo.deleteAll() } returns Unit
        every { split.execute(any(), any()) } returns fresh
        SeedVaultUseCase(repo, split).execute(force = true) shouldBe fresh
        verify(exactly = 1) { repo.deleteAll() }
        verify(exactly = 1) { split.execute(any(), any()) }
    }

    "GetEgressLogUseCase returns empty without ceremony" {
        val repo = mockk<CeremonyRepository>()
        every { repo.findLatest() } returns null
        GetEgressLogUseCase(repo).execute() shouldBe emptyList()
    }

    "GetEgressLogUseCase loads rows for latest ceremony" {
        val repo = mockk<CeremonyRepository>()
        val ceremony = Ceremony.create(
            commitments = listOf(byteArrayOf(1)),
            sealedNetworkConfig = byteArrayOf(2),
            at = fixedInstant,
        )
        val rows = listOf(
            EgressLogEntry(UUID.randomUUID(), ceremony.id, fixedInstant, "network-config only"),
        )
        every { repo.findLatest() } returns ceremony
        every { repo.findEgressLog(ceremony.id) } returns rows
        GetEgressLogUseCase(repo).execute() shouldBe rows
    }

    "AttestationDoc equals/hashCode use signature bytes" {
        val a = AttestationDoc("m", 1, "d", byteArrayOf(1, 2), true)
        val b = AttestationDoc("m", 1, "d", byteArrayOf(1, 2), true)
        val c = AttestationDoc("m", 1, "d", byteArrayOf(9), true)
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a shouldNotBe c
    }
})
