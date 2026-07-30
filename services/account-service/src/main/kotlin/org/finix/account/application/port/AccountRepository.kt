package org.finix.account.application.port

import org.finix.account.domain.Account
import java.util.UUID

/** Persistence port for the [Account] aggregate. */
interface AccountRepository {
    fun save(account: Account): Account
    fun findById(id: UUID): Account?
    fun findByOwner(ownerUserId: UUID): List<Account>
    fun findByAccountNumber(accountNumber: String): Account?
}
