package org.finix.account.domain

/**
 * Product shape of a deposit account.
 *
 * SAVINGS / CURRENT map to retail + SME products; WALLET is the offline/USSD inclusion balance.
 */
enum class AccountType {
    SAVINGS,
    CURRENT,
    WALLET,
}

/** Lifecycle of an [Account]. Only [ACTIVE] accounts accept reserve/credit mutations. */
enum class AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED,
}

/** Lifecycle of a funds hold on an account. */
enum class HoldStatus {
    OPEN,
    COMMITTED,
    RELEASED,
}
