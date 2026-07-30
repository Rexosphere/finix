package org.finix.account.application.usecase

import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ReleaseHoldUseCase(
    private val accounts: AccountRepository,
) {
    @Transactional
    fun execute(accountId: UUID, holdId: UUID): Account {
        val account = accounts.findById(accountId)
            ?: DomainError.NotFound("Account", accountId.toString()).raise()
        account.releaseHold(holdId)
        return accounts.save(account)
    }
}
