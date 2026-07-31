package org.finix.loan.application.usecase

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import org.finix.loan.application.ApplyLoanCommand
import org.finix.loan.application.DecideLoanCommand
import org.finix.loan.application.port.LoanRepository
import org.finix.loan.domain.DemoLoanIds
import org.finix.loan.domain.Loan
import org.finix.loan.domain.LoanStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ApplyLoanUseCaseTest : StringSpec({

    val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    "ApplyLoanUseCase persists a PENDING loan with schedule" {
        val repo = mockk<LoanRepository>()
        val saved = slot<Loan>()
        every { repo.save(capture(saved)) } answers { saved.captured }

        val result = ApplyLoanUseCase(repo, clock).execute(
            ApplyLoanCommand(
                borrowerUserId = DemoLoanIds.SME_USER_ID,
                accountId = DemoLoanIds.SME_ACCOUNT_ID,
                principal = "120000.00".lkr(),
                termMonths = 12,
            ),
        )

        result.status shouldBe LoanStatus.PENDING
        result.borrowerUserId shouldBe DemoLoanIds.SME_USER_ID
        result.accountId shouldBe DemoLoanIds.SME_ACCOUNT_ID
        result.schedule.size shouldBe 12
        verify(exactly = 1) { repo.save(any()) }
    }

    "ScoreAndDecideLoanUseCase approves a small low-risk loan" {
        val repo = mockk<LoanRepository>()
        val loan = Loan.apply(
            borrowerUserId = DemoLoanIds.SME_USER_ID,
            accountId = DemoLoanIds.SME_ACCOUNT_ID,
            principal = "40000.00".lkr(),
            clock = clock,
        )
        every { repo.findById(loan.id) } returns loan
        every { repo.save(any()) } answers { firstArg() }

        val result = ScoreAndDecideLoanUseCase(repo, clock).execute(
            DecideLoanCommand(loanId = loan.id, riskHint = "LOW"),
        )

        result.status shouldBe LoanStatus.APPROVED
        result.creditScore shouldBe 90
    }

    "ScoreAndDecideLoanUseCase rejects a large high-risk loan" {
        val repo = mockk<LoanRepository>()
        val loan = Loan.apply(
            borrowerUserId = DemoLoanIds.SME_USER_ID,
            accountId = DemoLoanIds.SME_ACCOUNT_ID,
            principal = "600000.00".lkr(),
            clock = clock,
        )
        every { repo.findById(loan.id) } returns loan
        every { repo.save(any()) } answers { firstArg() }

        val result = ScoreAndDecideLoanUseCase(repo, clock).execute(
            DecideLoanCommand(loanId = loan.id, riskHint = "HIGH"),
        )

        result.status shouldBe LoanStatus.REJECTED
        result.creditScore shouldBe 5
    }

    "ScoreAndDecideLoanUseCase raises NotFound when missing" {
        val repo = mockk<LoanRepository>()
        every { repo.findById(any()) } returns null
        val ex = shouldThrow<DomainException> {
            ScoreAndDecideLoanUseCase(repo, clock).execute(
                DecideLoanCommand(loanId = UUID.randomUUID()),
            )
        }
        (ex.error is DomainError.NotFound) shouldBe true
    }

    "GetLoanUseCase returns the loan" {
        val repo = mockk<LoanRepository>()
        val loan = Loan.apply(
            borrowerUserId = DemoLoanIds.SME_USER_ID,
            accountId = DemoLoanIds.SME_ACCOUNT_ID,
            principal = "1000.00".lkr(),
            clock = clock,
        )
        every { repo.findById(loan.id) } returns loan
        GetLoanUseCase(repo).execute(loan.id).id shouldBe loan.id
    }

    "GetLoanUseCase raises NotFound when missing" {
        val repo = mockk<LoanRepository>()
        every { repo.findById(any()) } returns null
        val ex = shouldThrow<DomainException> {
            GetLoanUseCase(repo).execute(UUID.randomUUID())
        }
        (ex.error is DomainError.NotFound) shouldBe true
    }

    "ListLoansUseCase filters by borrower when provided" {
        val repo = mockk<LoanRepository>()
        val loan = Loan.apply(
            borrowerUserId = DemoLoanIds.SME_USER_ID,
            accountId = DemoLoanIds.SME_ACCOUNT_ID,
            principal = "1000.00".lkr(),
            clock = clock,
        )
        every { repo.findByBorrower(DemoLoanIds.SME_USER_ID) } returns listOf(loan)
        ListLoansUseCase(repo).execute(DemoLoanIds.SME_USER_ID).size shouldBe 1
        verify(exactly = 1) { repo.findByBorrower(DemoLoanIds.SME_USER_ID) }
    }

    "ListLoansUseCase lists all when borrower omitted" {
        val repo = mockk<LoanRepository>()
        every { repo.findAll() } returns emptyList()
        ListLoansUseCase(repo).execute(null) shouldBe emptyList()
        verify(exactly = 1) { repo.findAll() }
    }

    "ScoreAndDecideLoanUseCase uses MEDIUM risk band" {
        val repo = mockk<LoanRepository>()
        val loan = Loan.apply(
            borrowerUserId = DemoLoanIds.SME_USER_ID,
            accountId = DemoLoanIds.SME_ACCOUNT_ID,
            principal = "40000.00".lkr(),
            clock = clock,
        )
        every { repo.findById(loan.id) } returns loan
        every { repo.save(any()) } answers { firstArg() }

        val result = ScoreAndDecideLoanUseCase(repo, clock).execute(
            DecideLoanCommand(loanId = loan.id, riskHint = "MEDIUM"),
        )
        result.creditScore shouldBe 70
        result.status shouldBe LoanStatus.APPROVED
    }
})
