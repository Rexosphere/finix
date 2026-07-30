package org.finix.orchestrator.domain

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.domainRequire
import java.time.Instant
import java.util.UUID

/**
 * Immutable saga aggregate for an internal transfer.
 *
 * [id] is also the ledger [transactionId] so journals and outbox events share one correlation key.
 * Transitions return a new instance; illegal moves raise [DomainError.Conflict].
 */
data class TransferSaga(
    val id: UUID,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Money,
    val state: SagaState,
    val holdId: UUID,
    /** True once the forward journal has been accepted — survives COMPENSATING for safe replay. */
    val ledgerPosted: Boolean = false,
    val failureReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        domainRequire(fromAccountId != toAccountId) {
            DomainError.Invalid(
                detail = "from and to accounts must differ",
                properties = mapOf(
                    "fromAccountId" to fromAccountId.toString(),
                    "toAccountId" to toAccountId.toString(),
                ),
            )
        }
        domainRequire(amount.isPositive) {
            DomainError.Invalid(
                detail = "transfer amount must be positive",
                properties = mapOf("amount" to amount.toString()),
            )
        }
    }

    fun markReserved(at: Instant = Instant.now()): TransferSaga =
        transition(SagaState.INITIATED, SagaState.FUNDS_RESERVED, at)

    fun markLedgerPosted(at: Instant = Instant.now()): TransferSaga =
        transition(SagaState.FUNDS_RESERVED, SagaState.LEDGER_POSTED, at).copy(ledgerPosted = true)

    fun markCreditApplied(at: Instant = Instant.now()): TransferSaga =
        transition(SagaState.LEDGER_POSTED, SagaState.CREDIT_APPLIED, at)

    fun markCompleted(at: Instant = Instant.now()): TransferSaga =
        transition(SagaState.CREDIT_APPLIED, SagaState.COMPLETED, at)

    fun beginCompensate(reason: String, at: Instant = Instant.now()): TransferSaga {
        domainRequire(state in COMPENSATABLE) {
            DomainError.Conflict(
                detail = "cannot begin compensation from state $state",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        return copy(state = SagaState.COMPENSATING, failureReason = reason, updatedAt = at)
    }

    fun markCompensated(at: Instant = Instant.now()): TransferSaga =
        transition(SagaState.COMPENSATING, SagaState.COMPENSATED, at)

    fun markFailed(reason: String, at: Instant = Instant.now()): TransferSaga {
        domainRequire(state in FAILABLE) {
            DomainError.Conflict(
                detail = "cannot mark failed from state $state",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        return copy(state = SagaState.FAILED, failureReason = reason, updatedAt = at)
    }

    private fun transition(expected: SagaState, next: SagaState, at: Instant): TransferSaga {
        domainRequire(state == expected) {
            DomainError.Conflict(
                detail = "illegal transition $state → $next (expected $expected)",
                properties = mapOf(
                    "sagaId" to id.toString(),
                    "from" to state.name,
                    "to" to next.name,
                    "expected" to expected.name,
                ),
            )
        }
        return copy(state = next, updatedAt = at)
    }

    companion object {
        private val COMPENSATABLE = setOf(
            SagaState.FUNDS_RESERVED,
            SagaState.LEDGER_POSTED,
            SagaState.CREDIT_APPLIED,
            SagaState.COMPENSATING,
        )

        private val FAILABLE = setOf(
            SagaState.INITIATED,
            SagaState.COMPENSATING,
        )

        fun initiate(
            fromAccountId: UUID,
            toAccountId: UUID,
            amount: Money,
            at: Instant = Instant.now(),
            id: UUID = UUID.randomUUID(),
            holdId: UUID = UUID.randomUUID(),
        ): TransferSaga = TransferSaga(
            id = id,
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amount = amount,
            state = SagaState.INITIATED,
            holdId = holdId,
            createdAt = at,
            updatedAt = at,
        )
    }
}
