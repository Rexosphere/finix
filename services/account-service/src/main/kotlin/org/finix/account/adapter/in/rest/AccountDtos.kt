package org.finix.account.adapter.`in`.rest

import org.finix.account.domain.Account
import org.finix.account.domain.AccountStatus
import org.finix.account.domain.AccountType
import org.finix.account.domain.HoldStatus
import org.finix.kernel.domain.Money
import java.util.UUID

data class OpenAccountRequest(
    val ownerUserId: UUID,
    val type: AccountType,
)

data class ReserveFundsRequest(
    val amount: Money,
    val holdId: UUID,
)

data class CreditAccountRequest(
    val amount: Money,
    val reference: String? = null,
)

data class HoldResponse(
    val id: UUID,
    val amount: Money,
    val createdAt: String,
    val status: HoldStatus,
)

data class AccountResponse(
    val id: UUID,
    val ownerUserId: UUID,
    val accountNumber: String,
    val type: AccountType,
    val status: AccountStatus,
    val currency: String,
    val availableBalance: Money,
    val heldBalance: Money,
    val ledgerBalance: Money,
    val version: Long,
    val openHolds: List<HoldResponse>,
)

data class SeedAccountsResponse(
    val accounts: List<AccountResponse>,
)

fun Account.toResponse(): AccountResponse = AccountResponse(
    id = id,
    ownerUserId = ownerUserId,
    accountNumber = accountNumber,
    type = type,
    status = status,
    currency = currency.currencyCode,
    availableBalance = availableBalance,
    heldBalance = heldBalance,
    ledgerBalance = ledgerBalance,
    version = version,
    openHolds = openHolds.map {
        HoldResponse(
            id = it.id,
            amount = it.amount,
            createdAt = it.createdAt.toString(),
            status = it.status,
        )
    },
)
