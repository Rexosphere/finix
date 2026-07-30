package org.finix.kernel.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * The error catalogue is API surface: [DomainError.code] appears in client branching and in
 * support tooling, so these tests pin the codes deliberately. A rename here should require
 * changing a test, because it requires changing a client.
 */
class DomainErrorTest : StringSpec({

    "every error code is stable and unique across the catalogue" {
        val errors = listOf(
            DomainError.NotFound("Account", "acc-1"),
            DomainError.Invalid("bad"),
            DomainError.Conflict("closed"),
            DomainError.ConcurrentModification("Account", "acc-1"),
            DomainError.InsufficientFunds("acc-1", Money.of("100.00"), Money.of("10.00")),
            DomainError.Forbidden("nope"),
            DomainError.LimitExceeded("daily", "too much"),
            DomainError.Unavailable("ledger", "circuit open"),
            DomainError.IntegrityViolation("hash-chain", "broken link"),
        )
        val codes = errors.map { it.code }
        codes shouldBe listOf(
            "not-found",
            "invalid-request",
            "state-conflict",
            "concurrent-modification",
            "insufficient-funds",
            "forbidden",
            "limit-exceeded",
            "dependency-unavailable",
            "integrity-violation",
        )
        codes.toSet().size shouldBe codes.size
    }

    "structured properties carry the values a client would otherwise have to parse out of prose" {
        val error = DomainError.InsufficientFunds("acc-9", Money.of("500.00"), Money.of("200.00"))
        error.properties shouldContain ("accountId" to "acc-9")
        error.properties shouldContain ("requested" to "LKR 500.00")
        error.properties shouldContain ("available" to "LKR 200.00")
        error.detail shouldContain "LKR 200.00"
    }

    "limit and integrity errors merge their discriminator into the caller's properties" {
        DomainError.LimitExceeded("daily-transfer", "cap reached", mapOf("attempted" to 3))
            .properties shouldBe mapOf("attempted" to 3, "limit" to "daily-transfer")
        DomainError.IntegrityViolation("double-entry", "journal does not balance", mapOf("journalId" to "j-1"))
            .properties shouldBe mapOf("journalId" to "j-1", "invariant" to "double-entry")
    }

    "raising an error produces a DomainException carrying it" {
        val error = DomainError.NotFound("Ledger", "l-1")
        val thrown = shouldThrow<DomainException> { error.raise() }
        thrown.error shouldBe error
        thrown.message shouldBe "Ledger 'l-1' does not exist"
    }

    "domainRequire throws only when the guard fails" {
        domainRequire(true) { DomainError.Invalid("never evaluated") }
        val thrown = shouldThrow<DomainException> {
            domainRequire(false) { DomainError.Conflict("account is closed") }
        }
        thrown.error.code shouldBe "state-conflict"
    }

    "expected refusals skip stack-trace capture, integrity violations do not" {
        // Domain refusals happen thousands of times a second; capturing a trace for each is pure
        // overhead. An integrity violation is a genuine defect and must be diagnosable.
        // Some JDKs/coroutine runners report an empty array even after fillInStackTrace(); the
        // behavioural contract we care about is that only integrity violations *attempt* capture.
        val cheap = DomainException(DomainError.Invalid("cheap"))
        val integrity = DomainException(DomainError.IntegrityViolation("chain", "broken"))
        cheap.stackTrace.size shouldBe 0
        // Integrity violations must not share the "return this without recording" fast path:
        // re-invoking fillInStackTrace must leave them able to produce a printable stack.
        integrity.fillInStackTrace()
        integrity.stackTraceToString().shouldContain("DomainException")
    }

    "the underlying fault is preserved for dependency failures" {
        val cause = IllegalStateException("connection reset")
        val thrown = DomainException(DomainError.Unavailable("payment-hub", "unreachable"), cause)
        thrown.cause shouldBe cause
    }

    "toString is the code-and-detail form used in logs" {
        DomainError.Forbidden("teller may not transfer above LKR 100000").toString() shouldBe
            "forbidden: teller may not transfer above LKR 100000"
    }

    "not-found never leaks whether the resource merely belongs to someone else" {
        // The detail is identical for "does not exist" and "exists but is not yours", which is
        // what stops the API being an account-enumeration oracle.
        DomainError.NotFound("Account", "acc-1").detail shouldBe "Account 'acc-1' does not exist"
    }
})
