package org.finix.account.application.usecase

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.finix.account.application.port.AccountNumberGenerator
import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.finix.account.domain.AccountType
import org.finix.account.domain.DemoAccounts
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import java.util.UUID

class AccountUseCasesTest : StringSpec({

    val owner = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")

    "OpenAccountUseCase persists a zero-balance account" {
        val repo = mockk<AccountRepository>()
        val numbers = mockk<AccountNumberGenerator>()
        every { numbers.next(AccountType.SAVINGS) } returns "FINIX-SAV-OPENED001"
        val saved = slot<Account>()
        every { repo.save(capture(saved)) } answers { saved.captured }

        val result = OpenAccountUseCase(repo, numbers).execute(owner, AccountType.SAVINGS)

        result.accountNumber shouldBe "FINIX-SAV-OPENED001"
        result.availableBalance shouldBe "0.00".lkr()
        result.type shouldBe AccountType.SAVINGS
        verify(exactly = 1) { repo.save(any()) }
    }

    "GetAccountUseCase raises NotFound when missing" {
        val repo = mockk<AccountRepository>()
        every { repo.findById(any()) } returns null
        val ex = shouldThrow<DomainException> {
            GetAccountUseCase(repo).execute(UUID.randomUUID())
        }
        (ex.error is DomainError.NotFound) shouldBe true
    }

    "ListAccountsUseCase returns owner accounts" {
        val repo = mockk<AccountRepository>()
        val account = Account.open(
            ownerUserId = owner,
            accountNumber = "FINIX-SAV-LIST0001",
            type = AccountType.SAVINGS,
        )
        every { repo.findByOwner(owner) } returns listOf(account)
        ListAccountsUseCase(repo).execute(owner) shouldHaveSize 1
    }

    "ReserveFundsUseCase reserves and saves" {
        val repo = mockk<AccountRepository>()
        val account = Account.open(
            ownerUserId = owner,
            accountNumber = "FINIX-SAV-RSV00001",
            type = AccountType.SAVINGS,
            initialAvailable = "500.00".lkr(),
        )
        every { repo.findById(account.id) } returns account
        every { repo.save(any()) } answers { firstArg() }

        val holdId = UUID.randomUUID()
        val result = ReserveFundsUseCase(repo).execute(account.id, "120.00".lkr(), holdId)

        result.availableBalance shouldBe "380.00".lkr()
        result.heldBalance shouldBe "120.00".lkr()
        verify { repo.save(account) }
    }

    "CommitHoldUseCase finalises a hold" {
        val repo = mockk<AccountRepository>()
        val account = Account.open(
            ownerUserId = owner,
            accountNumber = "FINIX-SAV-CMT00001",
            type = AccountType.SAVINGS,
            initialAvailable = "200.00".lkr(),
        )
        val holdId = UUID.randomUUID()
        account.reserve("80.00".lkr(), holdId)
        every { repo.findById(account.id) } returns account
        every { repo.save(any()) } answers { firstArg() }

        val result = CommitHoldUseCase(repo).execute(account.id, holdId)
        result.heldBalance shouldBe "0.00".lkr()
        result.availableBalance shouldBe "120.00".lkr()
    }

    "ReleaseHoldUseCase restores available funds" {
        val repo = mockk<AccountRepository>()
        val account = Account.open(
            ownerUserId = owner,
            accountNumber = "FINIX-SAV-REL00001",
            type = AccountType.SAVINGS,
            initialAvailable = "200.00".lkr(),
        )
        val holdId = UUID.randomUUID()
        account.reserve("80.00".lkr(), holdId)
        every { repo.findById(account.id) } returns account
        every { repo.save(any()) } answers { firstArg() }

        val result = ReleaseHoldUseCase(repo).execute(account.id, holdId)
        result.availableBalance shouldBe "200.00".lkr()
        result.heldBalance shouldBe "0.00".lkr()
    }

    "CreditAccountUseCase increases available" {
        val repo = mockk<AccountRepository>()
        val account = Account.open(
            ownerUserId = owner,
            accountNumber = "FINIX-CUR-CRD00001",
            type = AccountType.CURRENT,
            initialAvailable = "10.00".lkr(),
        )
        every { repo.findById(account.id) } returns account
        every { repo.save(any()) } answers { firstArg() }

        val result = CreditAccountUseCase(repo).execute(account.id, "5.00".lkr(), "txn-1")
        result.availableBalance shouldBe "15.00".lkr()
    }

    "SeedAccountsUseCase creates missing demo accounts with fixed ids" {
        val repo = mockk<AccountRepository>()
        every { repo.findById(any()) } returns null
        every { repo.save(any()) } answers { firstArg() }

        val seeded = SeedAccountsUseCase(repo).execute()

        seeded shouldHaveSize DemoAccounts.ALL.size
        seeded.map { it.id }.toSet() shouldBe DemoAccounts.ALL.map { it.accountId }.toSet()
        seeded.first { it.id == DemoAccounts.FARMER_ACCOUNT_ID }.availableBalance shouldBe
            DemoAccounts.FARMER_OPENING_BALANCE
        seeded.first { it.id == DemoAccounts.SME_ACCOUNT_ID }.availableBalance shouldBe
            DemoAccounts.SME_OPENING_BALANCE
        seeded.first { it.id == DemoAccounts.ELDER_ACCOUNT_ID }.availableBalance shouldBe
            DemoAccounts.ELDER_OPENING_BALANCE
        verify(exactly = DemoAccounts.ALL.size) { repo.save(any()) }
    }

    "SeedAccountsUseCase is idempotent when accounts already exist" {
        val repo = mockk<AccountRepository>()
        val existing = DemoAccounts.ALL.map { spec ->
            Account.open(
                id = spec.accountId,
                ownerUserId = spec.ownerUserId,
                accountNumber = spec.accountNumber,
                type = spec.type,
                initialAvailable = spec.openingBalance,
            )
        }
        existing.forEach { account ->
            every { repo.findById(account.id) } returns account
        }

        val seeded = SeedAccountsUseCase(repo).execute()
        seeded shouldHaveSize 3
        verify(exactly = 0) { repo.save(any()) }
    }
})
