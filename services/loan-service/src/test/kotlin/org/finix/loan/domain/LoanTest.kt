package org.finix.loan.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LoanTest : StringSpec({

    val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    fun pending(principal: String = "120000.00", termMonths: Int = 12): Loan =
        Loan.apply(
            borrowerUserId = DemoLoanIds.SME_USER_ID,
            accountId = DemoLoanIds.SME_ACCOUNT_ID,
            principal = principal.lkr(),
            termMonths = termMonths,
            clock = clock,
        )

    "apply creates PENDING loan with equal schedule that sums to principal" {
        val loan = pending("100.00", termMonths = 3)
        loan.status shouldBe LoanStatus.PENDING
        loan.schedule.size shouldBe 3
        loan.schedule.map { it.amount.minorUnits }.sum() shouldBe 10_000L
        loan.schedule.map { it.installmentNumber } shouldBe listOf(1, 2, 3)
    }

    "decide approves when score passes threshold" {
        val loan = pending()
        loan.decide(approved = true, score = 70, hint = "LOW", clock = clock)
        loan.status shouldBe LoanStatus.APPROVED
        loan.creditScore shouldBe 70
        loan.riskHint shouldBe "LOW"
        loan.decidedAt shouldBe Instant.parse("2026-07-31T12:00:00Z")
    }

    "decide rejects when score fails" {
        val loan = pending()
        loan.decide(approved = false, score = 40, hint = "HIGH", clock = clock)
        loan.status shouldBe LoanStatus.REJECTED
        loan.creditScore shouldBe 40
    }

    "decide is idempotent for the same outcome" {
        val loan = pending()
        loan.decide(approved = true, score = 70, hint = null, clock = clock)
        loan.decide(approved = true, score = 70, hint = null, clock = clock)
        loan.status shouldBe LoanStatus.APPROVED
    }

    "decide refuses flipping an already decided loan" {
        val loan = pending()
        loan.decide(approved = true, score = 70, hint = null, clock = clock)
        val ex = shouldThrow<DomainException> {
            loan.decide(approved = false, score = 20, hint = "HIGH", clock = clock)
        }
        (ex.error is DomainError.Conflict) shouldBe true
    }

    "apply refuses non-positive principal" {
        val ex = shouldThrow<DomainException> {
            Loan.apply(
                borrowerUserId = DemoLoanIds.SME_USER_ID,
                accountId = DemoLoanIds.SME_ACCOUNT_ID,
                principal = "0.00".lkr(),
                clock = clock,
            )
        }
        (ex.error is DomainError.Invalid) shouldBe true
    }

    "credit scoring is deterministic from amount and hint" {
        LoanCreditScoring.score("40000.00".lkr(), null) shouldBe 80
        LoanCreditScoring.score("40000.00".lkr(), "HIGH") shouldBe 55
        LoanCreditScoring.score("40000.00".lkr(), "MEDIUM") shouldBe 70
        LoanCreditScoring.score("100000.00".lkr(), null) shouldBe 65
        LoanCreditScoring.score("200000.00".lkr(), "LOW") shouldBe 60
        LoanCreditScoring.score("600000.00".lkr(), null) shouldBe 30
        LoanCreditScoring.isApproved(60) shouldBe true
        LoanCreditScoring.isApproved(59) shouldBe false
    }

    "apply refuses invalid term months" {
        val ex = shouldThrow<DomainException> {
            Loan.apply(
                borrowerUserId = DemoLoanIds.SME_USER_ID,
                accountId = DemoLoanIds.SME_ACCOUNT_ID,
                principal = "1000.00".lkr(),
                termMonths = 0,
                clock = clock,
            )
        }
        (ex.error is DomainError.Invalid) shouldBe true
    }

    "decide refuses invalid credit score" {
        val loan = pending()
        val ex = shouldThrow<DomainException> {
            loan.decide(approved = true, score = 101, hint = null, clock = clock)
        }
        (ex.error is DomainError.Invalid) shouldBe true
    }

    "decide refuses when loan is already disbursed" {
        val loan = pending()
        loan.decide(approved = true, score = 70, hint = null, clock = clock)
        // Force a non-decidable lifecycle state via reflection-free re-construction path:
        // APPROVED is decidable; DISBURSED is not — simulate by constructing directly.
        val disbursed = Loan(
            id = loan.id,
            borrowerUserId = loan.borrowerUserId,
            accountId = loan.accountId,
            principal = loan.principal,
            termMonths = loan.termMonths,
            status = LoanStatus.DISBURSED,
            schedule = loan.schedule,
            creditScore = 70,
            appliedAt = loan.appliedAt,
            decidedAt = loan.decidedAt,
        )
        val ex = shouldThrow<DomainException> {
            disbursed.decide(approved = true, score = 70, hint = null, clock = clock)
        }
        (ex.error is DomainError.Conflict) shouldBe true
    }

    "rejected loan cannot be flipped to approved" {
        val loan = pending()
        loan.decide(approved = false, score = 20, hint = "HIGH", clock = clock)
        val ex = shouldThrow<DomainException> {
            loan.decide(approved = true, score = 90, hint = "LOW", clock = clock)
        }
        (ex.error is DomainError.Conflict) shouldBe true
    }

    "rejected decide is idempotent" {
        val loan = pending()
        loan.decide(approved = false, score = 20, hint = null, clock = clock)
        loan.decide(approved = false, score = 20, hint = null, clock = clock)
        loan.status shouldBe LoanStatus.REJECTED
    }
})
