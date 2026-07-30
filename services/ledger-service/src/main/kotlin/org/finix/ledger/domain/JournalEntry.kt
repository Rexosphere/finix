package org.finix.ledger.domain

import org.finix.kernel.crypto.Hashing
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.domainRequire
import java.time.Instant
import java.util.UUID

/**
 * An immutable, hash-chained double-entry journal entry.
 *
 * Balance and single-currency checks live here so they cannot be bypassed by an adapter.
 * Canonical JSON serialisation (Jackson / RFC 8785 JCS) stays out of the domain — callers pass
 * a [canonicalize] function that turns [canonicalPayload] into the exact bytes that were hashed.
 */
data class JournalEntry(
    val id: UUID,
    val transactionId: UUID,
    val lines: List<JournalLine>,
    val prevHash: String,
    val entryHash: String,
    val recordedAt: Instant,
    val sequence: Long,
    /** The map that was (or must be) hashed into [entryHash]; also persisted as JSONB. */
    val payload: Map<String, Any?>,
) {
    init {
        require(lines.isNotEmpty()) { "Journal entry must have at least one line" }
        require(sequence > 0) { "Journal sequence must be positive, got $sequence" }
        require(prevHash.length == 64) { "prevHash must be 64 hex chars" }
        require(entryHash.length == 64) { "entryHash must be 64 hex chars" }
    }

    companion object {

        /**
         * Validate double-entry invariants without hashing.
         *
         * Used by the use case before it builds the canonical payload, and by tests that only
         * care about the accounting rule.
         */
        fun requireBalanced(lines: List<JournalLine>) {
            domainRequire(lines.isNotEmpty()) {
                DomainError.Invalid("Journal entry requires at least one line")
            }

            val currencies = lines.map { it.amount.currency }.toSet()
            domainRequire(currencies.size == 1) {
                DomainError.Invalid(
                    detail = "All journal lines must share one currency",
                    properties = mapOf("currencies" to currencies.map { it.currencyCode }),
                )
            }

            val currency = currencies.first()
            val debits = Money.sum(
                lines.filter { it.side == EntrySide.DEBIT }.map { it.amount },
                currency,
            )
            val credits = Money.sum(
                lines.filter { it.side == EntrySide.CREDIT }.map { it.amount },
                currency,
            )

            domainRequire(debits.minorUnits == credits.minorUnits) {
                DomainError.IntegrityViolation(
                    invariant = "balanced-journal",
                    detail = "Debits ($debits) must equal credits ($credits)",
                    properties = mapOf(
                        "debitMinor" to debits.minorUnits,
                        "creditMinor" to credits.minorUnits,
                        "currency" to currency.currencyCode,
                    ),
                )
            }

            // A journal that is all zeros on one side after filtering empty lists would already
            // fail the equality check against a non-empty opposite side; also refuse a zero total
            // so "balanced at zero" cannot sneak past as a no-op posting.
            domainRequire(debits.isPositive) {
                DomainError.Invalid("Journal must move a positive amount")
            }
        }

        /**
         * Build the deterministic payload map that becomes both the hash input and the stored
         * JSONB. Lines are sorted by accountId then side so two logically identical journals
         * always produce the same digest regardless of caller ordering.
         */
        fun canonicalPayload(
            transactionId: UUID,
            sequence: Long,
            recordedAt: Instant,
            lines: List<JournalLine>,
        ): Map<String, Any?> {
            val sortedLines = lines.sortedWith(
                compareBy({ it.accountId }, { it.side.name }),
            )
            return mapOf(
                "transactionId" to transactionId.toString(),
                "sequence" to sequence,
                "recordedAt" to recordedAt.toString(),
                "lines" to sortedLines.map { line ->
                    mapOf(
                        "accountId" to line.accountId.toString(),
                        "side" to line.side.name,
                        "amountMinor" to line.amount.minorUnits,
                        "currency" to line.amount.currency.currencyCode,
                    )
                },
            )
        }

        /**
         * Factory: validate balance, build payload, hash, return a fully formed entry.
         *
         * [canonicalize] is injected so the domain never imports Jackson — the application layer
         * supplies `CanonicalJson.canonicalBytes(mapper.valueToTree(map))`.
         */
        fun create(
            id: UUID,
            transactionId: UUID,
            lines: List<JournalLine>,
            prevHash: String,
            sequence: Long,
            recordedAt: Instant,
            canonicalize: (Map<String, Any?>) -> ByteArray,
        ): JournalEntry {
            requireBalanced(lines)
            val payload = canonicalPayload(transactionId, sequence, recordedAt, lines)
            val entryHash = Hashing.chain(prevHash, canonicalize(payload))
            return JournalEntry(
                id = id,
                transactionId = transactionId,
                lines = lines,
                prevHash = prevHash,
                entryHash = entryHash,
                recordedAt = recordedAt,
                sequence = sequence,
                payload = payload,
            )
        }

        /**
         * Rehydrate a persisted row without re-hashing. Used by the repository adapter.
         * Parameter list mirrors the table row; kept explicit so mapping stays obvious.
         */
        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            transactionId: UUID,
            lines: List<JournalLine>,
            prevHash: String,
            entryHash: String,
            recordedAt: Instant,
            sequence: Long,
            payload: Map<String, Any?>,
        ): JournalEntry = JournalEntry(
            id = id,
            transactionId = transactionId,
            lines = lines,
            prevHash = prevHash,
            entryHash = entryHash,
            recordedAt = recordedAt,
            sequence = sequence,
            payload = payload,
        )
    }
}

/**
 * Walk a sequence-ordered list of entries and report the first broken hash link.
 *
 * Pure function so property tests can inject a tampered entry without a database.
 */
object LedgerChain {

    fun verify(
        entries: List<JournalEntry>,
        canonicalize: (Map<String, Any?>) -> ByteArray,
    ): VerificationReport {
        var expectedPrev = Hashing.ZERO_DIGEST
        var expectedSequence = 1L
        var checked = 0

        for (entry in entries) {
            checked++
            if (entry.sequence != expectedSequence) {
                return VerificationReport.broken(
                    checkedEntries = checked,
                    firstBreakSequence = entry.sequence,
                    detail = "Expected sequence $expectedSequence but found ${entry.sequence}",
                )
            }
            if (entry.prevHash != expectedPrev) {
                return VerificationReport.broken(
                    checkedEntries = checked,
                    firstBreakSequence = entry.sequence,
                    detail = "prevHash mismatch at sequence ${entry.sequence}: " +
                        "expected $expectedPrev, stored ${entry.prevHash}",
                )
            }
            val recomputed = Hashing.chain(entry.prevHash, canonicalize(entry.payload))
            if (recomputed != entry.entryHash) {
                return VerificationReport.broken(
                    checkedEntries = checked,
                    firstBreakSequence = entry.sequence,
                    detail = "entryHash mismatch at sequence ${entry.sequence}: " +
                        "expected $recomputed, stored ${entry.entryHash}",
                )
            }
            expectedPrev = entry.entryHash
            expectedSequence++
        }
        return VerificationReport.ok(checked)
    }
}
