package org.finix.account.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AccountTest : StringSpec({

    val clock = Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC)
    val owner = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    fun open(available: String = "1000.00"): Account =
        Account.open(
            ownerUserId = owner,
            accountNumber = "FINIX-SAV-TEST00001",
            type = AccountType.SAVINGS,
            initialAvailable = available.lkr(),
        )

    "ledgerBalance is available plus held" {
        val account = open("500.00")
        account.reserve("200.00".lkr(), UUID.randomUUID(), clock)
        account.available shouldBe "300.00".lkr()
        account.heldBalance shouldBe "200.00".lkr()
        account.ledgerBalance shouldBe "500.00".lkr()
    }

    "reserve moves funds from available to held" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("250.00".lkr(), holdId, clock)
        account.availableBalance shouldBe "750.00".lkr()
        account.heldBalance shouldBe "250.00".lkr()
        account.openHolds.single().id shouldBe holdId
        account.openHolds.single().amount shouldBe "250.00".lkr()
    }

    "reserve is idempotent for the same holdId and amount" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("100.00".lkr(), holdId, clock)
        account.reserve("100.00".lkr(), holdId, clock)
        account.availableBalance shouldBe "900.00".lkr()
        account.heldBalance shouldBe "100.00".lkr()
        account.openHolds.size shouldBe 1
    }

    "reserve with same holdId but different amount conflicts" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("100.00".lkr(), holdId, clock)
        val ex = shouldThrow<DomainException> {
            account.reserve("50.00".lkr(), holdId, clock)
        }
        (ex.error is DomainError.Conflict) shouldBe true
    }

    "reserve refuses when available funds are insufficient" {
        val account = open("100.00")
        val ex = shouldThrow<DomainException> {
            account.reserve("100.01".lkr(), UUID.randomUUID(), clock)
        }
        val error = ex.error as DomainError.InsufficientFunds
        error.properties["accountId"] shouldBe account.id.toString()
    }

    "commitHold removes held funds from the account" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("400.00".lkr(), holdId, clock)
        account.commitHold(holdId)
        account.availableBalance shouldBe "600.00".lkr()
        account.heldBalance shouldBe "0.00".lkr()
        account.ledgerBalance shouldBe "600.00".lkr()
        account.openHolds shouldBe emptyList()
        account.holds.single().status shouldBe HoldStatus.COMMITTED
    }

    "commitHold is idempotent when already committed" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("50.00".lkr(), holdId, clock)
        account.commitHold(holdId)
        account.commitHold(holdId)
        account.heldBalance shouldBe "0.00".lkr()
        account.availableBalance shouldBe "950.00".lkr()
    }

    "releaseHold returns held funds to available" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("300.00".lkr(), holdId, clock)
        account.releaseHold(holdId)
        account.availableBalance shouldBe "1000.00".lkr()
        account.heldBalance shouldBe "0.00".lkr()
        account.openHolds shouldBe emptyList()
        account.holds.single().status shouldBe HoldStatus.RELEASED
    }

    "releaseHold is idempotent when already released" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("75.00".lkr(), holdId, clock)
        account.releaseHold(holdId)
        account.releaseHold(holdId)
        account.availableBalance shouldBe "1000.00".lkr()
    }

    "cannot commit a released hold" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("10.00".lkr(), holdId, clock)
        account.releaseHold(holdId)
        val ex = shouldThrow<DomainException> { account.commitHold(holdId) }
        (ex.error is DomainError.Conflict) shouldBe true
    }

    "cannot release a committed hold" {
        val account = open()
        val holdId = UUID.randomUUID()
        account.reserve("10.00".lkr(), holdId, clock)
        account.commitHold(holdId)
        val ex = shouldThrow<DomainException> { account.releaseHold(holdId) }
        (ex.error is DomainError.Conflict) shouldBe true
    }

    "credit increases available balance" {
        val account = open("100.00")
        account.credit("50.00".lkr())
        account.availableBalance shouldBe "150.00".lkr()
        account.ledgerBalance shouldBe "150.00".lkr()
    }

    "mutations refuse frozen accounts" {
        val account = open()
        account.freeze()
        shouldThrow<DomainException> { account.reserve("1.00".lkr(), UUID.randomUUID(), clock) }
        shouldThrow<DomainException> { account.credit("1.00".lkr()) }
    }

    "zero or negative amounts are invalid" {
        val account = open()
        shouldThrow<DomainException> { account.reserve("0.00".lkr(), UUID.randomUUID(), clock) }
        shouldThrow<DomainException> { account.credit("0.00".lkr()) }
    }

    "missing hold raises not-found" {
        val account = open()
        val ex = shouldThrow<DomainException> { account.commitHold(UUID.randomUUID()) }
        (ex.error is DomainError.NotFound) shouldBe true
    }
})
