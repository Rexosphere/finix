package org.finix.account.application.usecase

import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ReserveFundsUseCase(
    private val accounts: AccountRepository,
) {
    /**
     * Reserves [amount] under [holdId]. Idempotent: repeating the same holdId + amount
     * returns the existing reservation without double-holding.
     */
    @Transactional
    fun execute(accountId: UUID, amount: Money, holdId: UUID): Account {
        val account = accounts.findById(accountId)
            ?: DomainError.NotFound("Account", accountId.toString()).raise()
        account.reserve(amount, holdId)
        return accounts.save(account)
    }
}
