package org.finix.account.application.usecase

import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class GetAccountUseCase(
    private val accounts: AccountRepository,
) {
    @Transactional(readOnly = true)
    fun execute(accountId: UUID): Account =
        accounts.findById(accountId)
            ?: DomainError.NotFound("Account", accountId.toString()).raise()
}
