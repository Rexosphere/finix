package org.finix.ledger.application

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.finix.kernel.crypto.Hashing
import org.finix.kernel.crypto.MerkleTree
import org.finix.kernel.crypto.PostQuantum
import org.finix.ledger.application.port.AnchorPort
import org.finix.ledger.application.port.AnchorRepository
import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.application.usecase.AnchorWindowUseCase
import org.finix.ledger.config.AnchorSigningKeys
import org.finix.ledger.domain.EntrySide
import org.finix.ledger.domain.JournalEntry
import org.finix.ledger.domain.JournalLine
import org.finix.ledger.domain.LedgerHead
import org.finix.kernel.domain.lkr
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AnchorWindowUseCaseTest : StringSpec({
    val mapper = ObjectMapper().registerKotlinModule()
    val canonicalizer = LedgerCanonicalizer(mapper)
    val keys = PostQuantum.generateSigningKeyPair()
    val clock = Clock.fixed(Instant.parse("2026-07-30T15:01:00Z"), ZoneOffset.UTC)

    fun entry(seq: Long): JournalEntry = JournalEntry.create(
        id = UUID.randomUUID(),
        transactionId = UUID.randomUUID(),
        lines = listOf(
            JournalLine(UUID.randomUUID(), EntrySide.DEBIT, 25.lkr()),
            JournalLine(UUID.randomUUID(), EntrySide.CREDIT, 25.lkr()),
        ),
        prevHash = Hashing.ZERO_DIGEST,
        sequence = seq,
        recordedAt = Instant.parse("2026-07-30T15:00:00Z"),
        canonicalize = canonicalizer::bytes,
    )

    "anchors the unanchored window with a verifiable ML-DSA signature" {
        val entry = entry(1)
        val ledger = mockk<LedgerRepository>()
        val anchors = mockk<AnchorRepository>()
        val port = mockk<AnchorPort>()
        every { ledger.latestHead() } returns LedgerHead(entry.entryHash, 1L)
        every { anchors.findLatest() } returns null
        every { ledger.findSequenceRange(1L, 1L) } returns listOf(entry)
        every { port.publish(any()) } answers { firstArg() }
        every { anchors.save(any()) } answers { firstArg() }

        val useCase = AnchorWindowUseCase(
            ledger = ledger,
            anchors = anchors,
            anchorPort = port,
            signingKeys = AnchorSigningKeys(keys.private, keys.public),
            clock = clock,
        )
        val anchor = useCase.execute()
        anchor shouldNotBe null
        anchor!!.merkleRoot shouldBe MerkleTree.root(listOf(entry.entryHash))
        val message = AnchorWindowUseCase.signingMessage(
            anchor.merkleRoot,
            anchor.windowStartSeq,
            anchor.windowEndSeq,
            anchor.entryCount,
        )
        PostQuantum.verify(keys.public, message, anchor.signature) shouldBe true
        verify { anchors.save(anchor) }
    }

    "returns null when ledger is empty or already fully anchored" {
        val ledger = mockk<LedgerRepository>()
        val anchors = mockk<AnchorRepository>()
        val port = mockk<AnchorPort>()
        val useCase = AnchorWindowUseCase(
            ledger, anchors, port, AnchorSigningKeys(keys.private, keys.public), clock,
        )
        every { ledger.latestHead() } returns LedgerHead(Hashing.ZERO_DIGEST, 0L)
        useCase.execute() shouldBe null

        val existing = entry(1)
        every { ledger.latestHead() } returns LedgerHead(existing.entryHash, 1L)
        every { anchors.findLatest() } returns org.finix.ledger.domain.LedgerAnchor(
            id = UUID.randomUUID(),
            windowStartSeq = 1,
            windowEndSeq = 1,
            merkleRoot = "r",
            entryCount = 1,
            signature = byteArrayOf(1),
            publicKey = byteArrayOf(2),
            anchoredAt = Instant.parse("2026-07-30T15:01:00Z"),
        )
        useCase.execute() shouldBe null
    }
})

class InjectAndListAnchorsTest : StringSpec({
    "InjectTamperUseCase rejects non-positive sequence and delegates otherwise" {
        val ledger = mockk<LedgerRepository>(relaxed = true)
        val useCase = org.finix.ledger.application.usecase.InjectTamperUseCase(ledger)
        io.kotest.assertions.throwables.shouldThrow<org.finix.kernel.domain.DomainException> {
            useCase.execute(0)
        }
        useCase.execute(3)
        verify { ledger.injectTamper(3) }
    }

    "ListAnchorsUseCase returns repository rows" {
        val anchors = mockk<AnchorRepository>()
        every { anchors.findAll() } returns emptyList()
        org.finix.ledger.application.usecase.ListAnchorsUseCase(anchors).execute() shouldBe emptyList()
    }

    "LedgerAnchor equality uses signature bytes" {
        val id = UUID.randomUUID()
        val at = Instant.parse("2026-07-30T15:01:00Z")
        val a = org.finix.ledger.domain.LedgerAnchor(
            id, 1, 2, "root", 2, byteArrayOf(1, 2), byteArrayOf(3), at,
        )
        val b = org.finix.ledger.domain.LedgerAnchor(
            id, 1, 2, "root", 2, byteArrayOf(1, 2), byteArrayOf(3), at,
        )
        val c = org.finix.ledger.domain.LedgerAnchor(
            id, 1, 2, "root", 2, byteArrayOf(9), byteArrayOf(3), at,
        )
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a shouldNotBe c
    }
})
