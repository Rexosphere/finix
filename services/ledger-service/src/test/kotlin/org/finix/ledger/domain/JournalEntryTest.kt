package org.finix.ledger.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import org.finix.kernel.crypto.CanonicalJson
import org.finix.kernel.crypto.Hashing
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.lkr
import java.time.Instant
import java.util.UUID

class JournalEntryTest : StringSpec({

    val mapper = ObjectMapper().registerKotlinModule()
    val canonicalize: (Map<String, Any?>) -> ByteArray = { payload ->
        CanonicalJson.canonicalBytes(mapper.valueToTree(payload))
    }

    fun balancedLines(amount: Money = 100.lkr()): List<JournalLine> {
        val debitAccount = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val creditAccount = UUID.fromString("22222222-2222-2222-2222-222222222222")
        return listOf(
            JournalLine(debitAccount, EntrySide.DEBIT, amount),
            JournalLine(creditAccount, EntrySide.CREDIT, amount),
        )
    }

    "unbalanced journal is rejected as IntegrityViolation" {
        val lines = listOf(
            JournalLine(UUID.randomUUID(), EntrySide.DEBIT, 100.lkr()),
            JournalLine(UUID.randomUUID(), EntrySide.CREDIT, 50.lkr()),
        )
        val ex = shouldThrow<DomainException> {
            JournalEntry.requireBalanced(lines)
        }
        (ex.error is DomainError.IntegrityViolation) shouldBe true
        ex.error.code shouldBe "integrity-violation"
    }

    "empty journal is rejected as Invalid" {
        val ex = shouldThrow<DomainException> {
            JournalEntry.requireBalanced(emptyList())
        }
        (ex.error is DomainError.Invalid) shouldBe true
    }

    "mixed currencies are rejected" {
        val usd = Money.ofMinor(100, java.util.Currency.getInstance("USD"))
        val lines = listOf(
            JournalLine(UUID.randomUUID(), EntrySide.DEBIT, 100.lkr()),
            JournalLine(UUID.randomUUID(), EntrySide.CREDIT, usd),
        )
        val ex = shouldThrow<DomainException> {
            JournalEntry.requireBalanced(lines)
        }
        (ex.error is DomainError.Invalid) shouldBe true
        ex.error.detail shouldContain "currency"
    }

    "balanced journal hashes and chains from ZERO_DIGEST" {
        val entry = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = Instant.parse("2026-07-30T12:00:00Z"),
            canonicalize = canonicalize,
        )
        entry.prevHash shouldBe Hashing.ZERO_DIGEST
        entry.entryHash shouldNotBe Hashing.ZERO_DIGEST
        entry.entryHash.length shouldBe 64

        val recomputed = Hashing.chain(entry.prevHash, canonicalize(entry.payload))
        recomputed shouldBe entry.entryHash
    }

    "second entry chains from the first entry hash" {
        val first = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(10.lkr()),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = Instant.parse("2026-07-30T12:00:00Z"),
            canonicalize = canonicalize,
        )
        val second = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(25.lkr()),
            prevHash = first.entryHash,
            sequence = 2,
            recordedAt = Instant.parse("2026-07-30T12:00:01Z"),
            canonicalize = canonicalize,
        )
        second.prevHash shouldBe first.entryHash
        LedgerChain.verify(listOf(first, second), canonicalize).valid shouldBe true
    }

    "line order does not change the entry hash" {
        val a = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val b = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val amount = 75.lkr()
        val recordedAt = Instant.parse("2026-07-30T12:00:00Z")
        val tx = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

        val forward = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = tx,
            lines = listOf(
                JournalLine(a, EntrySide.DEBIT, amount),
                JournalLine(b, EntrySide.CREDIT, amount),
            ),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = recordedAt,
            canonicalize = canonicalize,
        )
        val reverse = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = tx,
            lines = listOf(
                JournalLine(b, EntrySide.CREDIT, amount),
                JournalLine(a, EntrySide.DEBIT, amount),
            ),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = recordedAt,
            canonicalize = canonicalize,
        )
        forward.entryHash shouldBe reverse.entryHash
    }

    "verify detects a tampered entry hash" {
        val entry = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = Instant.parse("2026-07-30T12:00:00Z"),
            canonicalize = canonicalize,
        )
        val tampered = entry.copy(entryHash = "ff".repeat(32))
        val report = LedgerChain.verify(listOf(tampered), canonicalize)
        report.valid shouldBe false
        report.firstBreakSequence shouldBe 1L
        report.detail!!.shouldContain("entryHash mismatch")
    }

    "verify detects a broken prevHash link" {
        val first = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(10.lkr()),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = Instant.parse("2026-07-30T12:00:00Z"),
            canonicalize = canonicalize,
        )
        val second = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(20.lkr()),
            prevHash = first.entryHash,
            sequence = 2,
            recordedAt = Instant.parse("2026-07-30T12:00:01Z"),
            canonicalize = canonicalize,
        ).copy(prevHash = Hashing.ZERO_DIGEST)

        val report = LedgerChain.verify(listOf(first, second), canonicalize)
        report.valid shouldBe false
        report.firstBreakSequence shouldBe 2L
    }

    "zero-amount line is rejected at construction" {
        shouldThrow<IllegalArgumentException> {
            JournalLine(UUID.randomUUID(), EntrySide.DEBIT, Money.zero())
        }.message!!.shouldContain("positive")
    }

    "verify detects a sequence gap" {
        val first = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(10.lkr()),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = Instant.parse("2026-07-30T12:00:00Z"),
            canonicalize = canonicalize,
        )
        val third = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(20.lkr()),
            prevHash = first.entryHash,
            sequence = 3,
            recordedAt = Instant.parse("2026-07-30T12:00:01Z"),
            canonicalize = canonicalize,
        )
        val report = LedgerChain.verify(listOf(first, third), canonicalize)
        report.valid shouldBe false
        report.firstBreakSequence shouldBe 3L
        report.detail!!.shouldContain("Expected sequence 2")
    }

    "empty chain verifies as valid with zero checked" {
        val report = LedgerChain.verify(emptyList(), canonicalize)
        report.valid shouldBe true
        report.checkedEntries shouldBe 0
        report.firstBreakSequence shouldBe null
    }

    "rehydrate round-trips without rehashing" {
        val created = JournalEntry.create(
            id = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            lines = balancedLines(),
            prevHash = Hashing.ZERO_DIGEST,
            sequence = 1,
            recordedAt = Instant.parse("2026-07-30T12:00:00Z"),
            canonicalize = canonicalize,
        )
        val restored = JournalEntry.rehydrate(
            id = created.id,
            transactionId = created.transactionId,
            lines = created.lines,
            prevHash = created.prevHash,
            entryHash = created.entryHash,
            recordedAt = created.recordedAt,
            sequence = created.sequence,
            payload = created.payload,
        )
        restored shouldBe created
    }

    "any positive balanced amount produces a stable chain link" {
        checkAll(Arb.long(1L..1_000_000L), Arb.uuid(), Arb.uuid()) { minor, debitId, creditId ->
            // Avoid the degenerate case where both accounts collide — still balanced but
            // unusual; property cares about hashing, not account distinctness.
            val amount = Money.ofMinor(minor)
            val lines = listOf(
                JournalLine(debitId, EntrySide.DEBIT, amount),
                JournalLine(creditId, EntrySide.CREDIT, amount),
            )
            val entry = JournalEntry.create(
                id = UUID.randomUUID(),
                transactionId = UUID.randomUUID(),
                lines = lines,
                prevHash = Hashing.ZERO_DIGEST,
                sequence = 1,
                recordedAt = Instant.parse("2026-01-01T00:00:00Z"),
                canonicalize = canonicalize,
            )
            Hashing.chain(entry.prevHash, canonicalize(entry.payload)) shouldBe entry.entryHash
        }
    }
})

class LedgerHeadTest : StringSpec({
    "genesis is ZERO_DIGEST at sequence 0" {
        LedgerHead.GENESIS.latestHash shouldBe Hashing.ZERO_DIGEST
        LedgerHead.GENESIS.latestSequence shouldBe 0L
        LedgerHead.GENESIS.nextSequence() shouldBe 1L
    }

    "rejects negative sequence" {
        shouldThrow<IllegalArgumentException> {
            LedgerHead(Hashing.ZERO_DIGEST, -1L)
        }
    }

    "rejects non-64-char hash" {
        shouldThrow<IllegalArgumentException> {
            LedgerHead("abc", 1L)
        }
    }
})
