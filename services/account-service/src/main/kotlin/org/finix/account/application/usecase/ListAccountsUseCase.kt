package org.finix.account.application.usecase

import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ListAccountsUseCase(
    private val accounts: AccountRepository,
) {
    @Transactional(readOnly = true)
    fun execute(ownerUserId: UUID): List<Account> = accounts.findByOwner(ownerUserId)
}
