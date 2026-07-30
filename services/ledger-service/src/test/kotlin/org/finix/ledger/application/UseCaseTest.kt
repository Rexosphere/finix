package org.finix.ledger.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.finix.kernel.crypto.Hashing
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.application.usecase.GetJournalUseCase
import org.finix.ledger.application.usecase.GetProofUseCase
import org.finix.ledger.application.usecase.PostJournalUseCase
import org.finix.ledger.application.usecase.VerifyLedgerUseCase
import org.finix.ledger.domain.EntrySide
import org.finix.ledger.domain.JournalEntry
import org.finix.ledger.domain.JournalLine
import org.finix.ledger.domain.LedgerHead
import org.finix.ledger.domain.VerificationReport
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PostJournalUseCaseTest : StringSpec({

    val mapper = ObjectMapper().registerKotlinModule()
    val canonicalizer = LedgerCanonicalizer(mapper)
    val fixedInstant = Instant.parse("2026-07-30T15:00:00Z")
    val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    fun lines(amount: org.finix.kernel.domain.Money = 100.lkr()) = listOf(
        JournalLine(UUID.fromString("11111111-1111-1111-1111-111111111111"), EntrySide.DEBIT, amount),
        JournalLine(UUID.fromString("22222222-2222-2222-2222-222222222222"), EntrySide.CREDIT, amount),
    )

    "posts a balanced journal against the current head" {
        val repo = mockk<LedgerRepository>(relaxed = true)
        every { repo.findByTransactionId(any()) } returns null
        every { repo.latestHead() } returns LedgerHead.GENESIS

        val useCase = PostJournalUseCase(repo, canonicalizer, clock)
        val txId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val entry = useCase(txId, lines())

        entry.transactionId shouldBe txId
        entry.sequence shouldBe 1L
        entry.prevHash shouldBe Hashing.ZERO_DIGEST
        entry.recordedAt shouldBe fixedInstant
        verify(exactly = 1) { repo.append(entry) }
    }

    "chains the second post from the previous entry hash" {
        val repo = mockk<LedgerRepository>(relaxed = true)
        val firstHash = "ab".repeat(32)
        every { repo.findByTransactionId(any()) } returns null
        every { repo.latestHead() } returns LedgerHead(firstHash, 1L)

        val useCase = PostJournalUseCase(repo, canonicalizer, clock)
        val entry = useCase(UUID.randomUUID(), lines(50.lkr()))

        entry.sequence shouldBe 2L
        entry.prevHash shouldBe firstHash
        verify(exactly = 1) { repo.append(match { it.prevHash == firstHash && it.sequence == 2L }) }
    }

    "rejects unbalanced lines before appending" {
        val repo = mockk<LedgerRepository>(relaxed = true)
        every { repo.findByTransactionId(any()) } returns null

        val useCase = PostJournalUseCase(repo, canonicalizer, clock)
        val unbalanced = listOf(
            JournalLine(UUID.randomUUID(), EntrySide.DEBIT, 100.lkr()),
            JournalLine(UUID.randomUUID(), EntrySide.CREDIT, 40.lkr()),
        )
        val ex = shouldThrow<DomainException> { useCase(UUID.randomUUID(), unbalanced) }
        (ex.error is DomainError.IntegrityViolation) shouldBe true
        verify(exactly = 0) { repo.append(any()) }
        verify(exactly = 0) { repo.latestHead() }
    }

    "duplicate transactionId is a Conflict" {
        val repo = mockk<LedgerRepository>()
        val txId = UUID.randomUUID()
        val existing = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = txId,
            lines = lines(),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = fixedInstant,
            canonicalize = canonicalizer::bytes,
        )
        every { repo.findByTransactionId(txId) } returns existing

        val useCase = PostJournalUseCase(repo, canonicalizer, clock)
        val ex = shouldThrow<DomainException> { useCase(txId, lines()) }
        (ex.error is DomainError.Conflict) shouldBe true
    }
})

class GetJournalUseCaseTest : StringSpec({
    "missing journal raises NotFound" {
        val repo = mockk<LedgerRepository>()
        every { repo.findByTransactionId(any()) } returns null
        val useCase = GetJournalUseCase(repo)
        val ex = shouldThrow<DomainException> { useCase(UUID.randomUUID()) }
        (ex.error is DomainError.NotFound) shouldBe true
    }
})

class VerifyLedgerUseCaseTest : StringSpec({
    "delegates to the repository chain walk" {
        val repo = mockk<LedgerRepository>()
        val report = VerificationReport.ok(3)
        every { repo.verifyChain() } returns report
        VerifyLedgerUseCase(repo)() shouldBe report
    }
})

class GetProofUseCaseTest : StringSpec({
    "returns entry hashes as the M2 inclusion stub" {
        val mapper = ObjectMapper().registerKotlinModule()
        val canonicalizer = LedgerCanonicalizer(mapper)
        val entry = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = listOf(
                JournalLine(UUID.randomUUID(), EntrySide.DEBIT, 10.lkr()),
                JournalLine(UUID.randomUUID(), EntrySide.CREDIT, 10.lkr()),
            ),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = Instant.parse("2026-07-30T15:00:00Z"),
            canonicalize = canonicalizer::bytes,
        )
        val repo = mockk<LedgerRepository>()
        every { repo.findByTransactionId(entry.transactionId) } returns entry

        val proof = GetProofUseCase(repo)(entry.transactionId)
        proof.entryHash shouldBe entry.entryHash
        proof.prevHash shouldBe entry.prevHash
        proof.inclusion shouldBe entry.entryHash
    }
})
