package org.finix.account.application.usecase

import org.finix.account.application.port.AccountNumberGenerator
import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.finix.account.domain.AccountType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OpenAccountUseCase(
    private val accounts: AccountRepository,
    private val accountNumbers: AccountNumberGenerator,
) {
    @Transactional
    fun execute(ownerUserId: UUID, type: AccountType): Account {
        val account = Account.open(
            ownerUserId = ownerUserId,
            accountNumber = accountNumbers.next(type),
            type = type,
        )
        return accounts.save(account)
    }
}
