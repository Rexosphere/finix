package org.finix.account.application.usecase

import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.finix.account.domain.DemoAccounts
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Opens the three blueprint persona accounts with fixed ids and opening balances so
 * orchestrator demos remain stable across `make demo` runs.
 *
 * Idempotent: an already-seeded account id is left untouched (balances are not reset).
 */
@Service
class SeedAccountsUseCase(
    private val accounts: AccountRepository,
) {
    @Transactional
    fun execute(): List<Account> = DemoAccounts.ALL.map { spec ->
        accounts.findById(spec.accountId) ?: accounts.save(
            Account.open(
                id = spec.accountId,
                ownerUserId = spec.ownerUserId,
                accountNumber = spec.accountNumber,
                type = spec.type,
                initialAvailable = spec.openingBalance,
            ),
        )
    }
}
