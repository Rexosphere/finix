package org.finix.account.application.usecase

import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Credits an account — used for the destination leg of a transfer and for saga compensation
 * when a debit must be undone by returning funds to the source.
 *
 * [reference] is accepted for audit correlation at the adapter edge; the domain only cares
 * about the monetary effect.
 */
@Service
class CreditAccountUseCase(
    private val accounts: AccountRepository,
) {
    @Transactional
    @Suppress("UnusedParameter")
    fun execute(accountId: UUID, amount: Money, reference: String?): Account {
        val account = accounts.findById(accountId)
            ?: DomainError.NotFound("Account", accountId.toString()).raise()
        account.credit(amount)
        return accounts.save(account)
    }
}
