package org.finix.account.domain

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.domainRequire
import java.time.Clock
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * A customer deposit account with available vs held balances.
 *
 * Holds live on the aggregate (not a separate root) so reserve/commit/release stay one
 * consistency boundary under optimistic locking. [available] is spendable; [ledgerBalance]
 * is what the customer "has" including money already earmarked for in-flight transfers.
 */
class Account(
    val id: UUID,
    val ownerUserId: UUID,
    val accountNumber: String,
    val type: AccountType,
    status: AccountStatus,
    val currency: Currency = Money.LKR,
    availableBalance: Money,
    heldBalance: Money,
    version: Long = 0,
    holds: List<Hold> = emptyList(),
) {
    var status: AccountStatus = status
        private set

    var availableBalance: Money = availableBalance
        private set

    var heldBalance: Money = heldBalance
        private set

    var version: Long = version
        private set

    private val _holds: MutableList<Hold> = holds.toMutableList()

    val holds: List<Hold> get() = _holds.toList()

    val openHolds: List<Hold> get() = _holds.filter { it.status == HoldStatus.OPEN }

    /** Spendable balance — money not reserved for an in-flight transfer. */
    val available: Money get() = availableBalance

    /** Book balance: available + held. */
    val ledgerBalance: Money get() = availableBalance + heldBalance

    /**
     * Moves [amount] from available into held under [holdId].
     *
     * Idempotent on [holdId]: a second reserve with the same id and amount is a no-op.
     * A second reserve with the same id but a different amount is a conflict.
     */
    fun reserve(amount: Money, holdId: UUID, clock: Clock = Clock.systemUTC()) {
        requireActive()
        requirePositive(amount)
        requireSameCurrency(amount)

        val existing = _holds.find { it.id == holdId }
        if (existing != null) {
            domainRequire(existing.status == HoldStatus.OPEN) {
                DomainError.Conflict(
                    detail = "Hold '$holdId' is already ${existing.status.name.lowercase()}",
                    properties = mapOf("holdId" to holdId.toString(), "status" to existing.status.name),
                )
            }
            domainRequire(existing.amount == amount) {
                DomainError.Conflict(
                    detail = "Hold '$holdId' already exists for ${existing.amount}, not $amount",
                    properties = mapOf(
                        "holdId" to holdId.toString(),
                        "existingAmount" to existing.amount.toString(),
                        "requestedAmount" to amount.toString(),
                    ),
                )
            }
            return
        }

        domainRequire(availableBalance >= amount) {
            DomainError.InsufficientFunds(
                accountId = id.toString(),
                requested = amount,
                available = availableBalance,
            )
        }

        availableBalance -= amount
        heldBalance += amount
        _holds += Hold(id = holdId, amount = amount, createdAt = Instant.now(clock), status = HoldStatus.OPEN)
    }

    /**
     * Finalises a hold: held funds leave the account (debit side of a completed transfer).
     * Idempotent if the hold is already [HoldStatus.COMMITTED].
     */
    fun commitHold(holdId: UUID) {
        requireActive()
        val hold = requireHold(holdId)
        if (hold.status == HoldStatus.COMMITTED) return
        domainRequire(hold.status == HoldStatus.OPEN) {
            DomainError.Conflict(
                detail = "Hold '$holdId' cannot be committed from status ${hold.status}",
                properties = mapOf("holdId" to holdId.toString(), "status" to hold.status.name),
            )
        }
        heldBalance -= hold.amount
        replaceHold(hold.copy(status = HoldStatus.COMMITTED))
    }

    /**
     * Cancels a hold and returns the reserved amount to available (saga compensation).
     * Idempotent if the hold is already [HoldStatus.RELEASED].
     */
    fun releaseHold(holdId: UUID) {
        requireActive()
        val hold = requireHold(holdId)
        if (hold.status == HoldStatus.RELEASED) return
        domainRequire(hold.status == HoldStatus.OPEN) {
            DomainError.Conflict(
                detail = "Hold '$holdId' cannot be released from status ${hold.status}",
                properties = mapOf("holdId" to holdId.toString(), "status" to hold.status.name),
            )
        }
        heldBalance -= hold.amount
        availableBalance += hold.amount
        replaceHold(hold.copy(status = HoldStatus.RELEASED))
    }

    /** Credits available balance — compensation credit or incoming transfer leg. */
    fun credit(amount: Money) {
        requireActive()
        requirePositive(amount)
        requireSameCurrency(amount)
        availableBalance += amount
    }

    fun freeze() {
        domainRequire(status == AccountStatus.ACTIVE) {
            DomainError.Conflict("Account '$id' cannot be frozen from status $status")
        }
        status = AccountStatus.FROZEN
    }

    fun close() {
        domainRequire(status != AccountStatus.CLOSED) {
            DomainError.Conflict("Account '$id' is already closed")
        }
        domainRequire(openHolds.isEmpty()) {
            DomainError.Conflict("Account '$id' still has open holds and cannot be closed")
        }
        domainRequire(ledgerBalance.isZero) {
            DomainError.Conflict("Account '$id' still holds a non-zero ledger balance")
        }
        status = AccountStatus.CLOSED
    }

    private fun requireHold(holdId: UUID): Hold =
        _holds.find { it.id == holdId }
            ?: DomainError.NotFound("Hold", holdId.toString()).raise()

    private fun replaceHold(updated: Hold) {
        val index = _holds.indexOfFirst { it.id == updated.id }
        domainRequire(index >= 0) { DomainError.NotFound("Hold", updated.id.toString()) }
        _holds[index] = updated
    }

    private fun requireActive() {
        domainRequire(status == AccountStatus.ACTIVE) {
            DomainError.Conflict(
                detail = "Account '$id' is $status and cannot accept balance mutations",
                properties = mapOf("accountId" to id.toString(), "status" to status.name),
            )
        }
    }

    private fun requirePositive(amount: Money) {
        domainRequire(amount.isPositive) {
            DomainError.Invalid(
                detail = "Amount must be positive, got $amount",
                properties = mapOf("amount" to amount.toString()),
            )
        }
    }

    private fun requireSameCurrency(amount: Money) {
        domainRequire(amount.currency == currency) {
            DomainError.Invalid(
                detail = "Currency mismatch: account is ${currency.currencyCode}, amount is ${amount.currency.currencyCode}",
                properties = mapOf(
                    "accountCurrency" to currency.currencyCode,
                    "amountCurrency" to amount.currency.currencyCode,
                ),
            )
        }
    }

    companion object {
        fun open(
            id: UUID = UUID.randomUUID(),
            ownerUserId: UUID,
            accountNumber: String,
            type: AccountType,
            currency: Currency = Money.LKR,
            initialAvailable: Money = Money.zero(currency),
        ): Account {
            domainRequire(initialAvailable.currency == currency) {
                DomainError.Invalid("Initial balance currency must match account currency")
            }
            domainRequire(!initialAvailable.isNegative) {
                DomainError.Invalid("Initial balance cannot be negative")
            }
            return Account(
                id = id,
                ownerUserId = ownerUserId,
                accountNumber = accountNumber,
                type = type,
                status = AccountStatus.ACTIVE,
                currency = currency,
                availableBalance = initialAvailable,
                heldBalance = Money.zero(currency),
                version = 0,
                holds = emptyList(),
            )
        }
    }
}
