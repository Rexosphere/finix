package org.finix.account.application.port

import org.finix.account.domain.AccountType

/** Generates unique human-readable account numbers for newly opened accounts. */
fun interface AccountNumberGenerator {
    fun next(type: AccountType): String
}
