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
    /**
     * True once the sender's hold has been committed. The debit is final at that point: the hold
     * can no longer be released, so compensation has to return the funds with a credit instead.
     */
    val holdCommitted: Boolean = false,
    /**
     * True when the recipient credit was attempted and its outcome could not be established.
     *
     * The money may or may not have reached the recipient, so no compensation — automatic or
     * operator-triggered — may return the sender's funds until the two sides are reconciled.
     */
    val creditOutcomeUnknown: Boolean = false,
    /**
     * True once account-service has *definitively refused* the recipient credit — the only durable
     * proof that the money did not move and that the committed debit may therefore be returned.
     *
     * Without it, a saga sitting at [SagaState.LEDGER_POSTED] with [holdCommitted] is ambiguous:
     * the credit may have landed and simply not been recorded. Compensation must refuse that row,
     * so the absence of this fact is what keeps an unrecorded outcome unreversible.
     */
    val creditRefused: Boolean = false,
    /**
     * True once a compensating refund to the sender has been *attempted*.
     *
     * Persisted before the call, never after: `CreditAccountUseCase` ignores its reference, so a
     * second attempt pays the sender twice. A crash between this write and the credit costs an
     * automatic retry; a crash the other way round would cost the amount.
     */
    val refundAttempted: Boolean = false,
    val failureReason: String? = null,
    val riskScore: Int? = null,
    val riskDecision: String? = null,
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

    /**
     * Records the gate outcome. [score] is null when risk-ai-service produced no usable assessment —
     * the decision is then the fail-closed default, and the null score is what distinguishes a
     * degraded gate from a scored one in the audit trail.
     */
    fun withRisk(score: Int?, decision: String, at: Instant = Instant.now()): TransferSaga =
        copy(riskScore = score, riskDecision = decision, updatedAt = at)

    fun markAwaitingStepUp(at: Instant = Instant.now()): TransferSaga =
        transition(SagaState.INITIATED, SagaState.AWAITING_STEP_UP, at)

    fun markBlocked(reason: String, at: Instant = Instant.now()): TransferSaga {
        domainRequire(state == SagaState.INITIATED || state == SagaState.AWAITING_STEP_UP) {
            DomainError.Conflict(
                detail = "cannot block from state $state",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        return copy(state = SagaState.BLOCKED, failureReason = reason, updatedAt = at)
    }

    fun markReserved(at: Instant = Instant.now()): TransferSaga {
        domainRequire(state == SagaState.INITIATED || state == SagaState.AWAITING_STEP_UP) {
            DomainError.Conflict(
                detail = "illegal transition $state → FUNDS_RESERVED",
                properties = mapOf("sagaId" to id.toString(), "from" to state.name, "to" to "FUNDS_RESERVED"),
            )
        }
        return copy(state = SagaState.FUNDS_RESERVED, updatedAt = at)
    }

    fun markLedgerPosted(at: Instant = Instant.now()): TransferSaga =
        transition(SagaState.FUNDS_RESERVED, SagaState.LEDGER_POSTED, at).copy(ledgerPosted = true)

    /**
     * Records that the sender's hold is committed. Deliberately not a state change — it is a fact
     * about the debit that has to survive into COMPENSATING, exactly like [ledgerPosted].
     */
    fun markHoldCommitted(at: Instant = Instant.now()): TransferSaga {
        domainRequire(state == SagaState.LEDGER_POSTED) {
            DomainError.Conflict(
                detail = "cannot commit the hold from state $state",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        return copy(holdCommitted = true, updatedAt = at)
    }

    /**
     * Freezes the saga because a credit neither clearly succeeded nor clearly failed — either the
     * recipient's credit (from LEDGER_POSTED) or the compensating refund back to the sender (from
     * COMPENSATING). Both are non-idempotent credits, so both must be frozen rather than retried.
     *
     * Requires [holdCommitted]: an unknown credit outcome is only meaningful once the sender's
     * debit is final. Freezing a saga whose hold is still OPEN would block the release that is
     * the correct — and idempotent — way to return those funds.
     *
     * Like [markHoldCommitted] this is a fact rather than a state change: the saga stays where the
     * money left it, carries [reason], and becomes ineligible for compensation until reconciled.
     */
    fun markCreditOutcomeUnknown(reason: String, at: Instant = Instant.now()): TransferSaga {
        domainRequire(holdCommitted) {
            DomainError.Conflict(
                detail = "cannot record an unknown credit outcome before the sender's hold is committed",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        domainRequire(state == SagaState.LEDGER_POSTED || state == SagaState.COMPENSATING) {
            DomainError.Conflict(
                detail = "cannot record an unknown credit outcome from state $state",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        return copy(creditOutcomeUnknown = true, failureReason = reason, updatedAt = at)
    }

    /**
     * Records that account-service definitively refused the recipient credit.
     *
     * This is the proof [creditRefused] describes, and it is written *before* compensation starts:
     * compensation of a committed debit is permitted only for a saga carrying it, so recording it
     * afterwards would leave the reversal unjustified for as long as the write took.
     */
    fun markCreditRefused(reason: String, at: Instant = Instant.now()): TransferSaga {
        domainRequire(holdCommitted) {
            DomainError.Conflict(
                detail = "cannot record a refused credit before the sender's hold is committed",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        domainRequire(state == SagaState.LEDGER_POSTED) {
            DomainError.Conflict(
                detail = "cannot record a refused credit from state $state",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        return copy(creditRefused = true, failureReason = reason, updatedAt = at)
    }

    /**
     * Records that the compensating refund is about to be issued. Written before the call, so a
     * failure anywhere afterwards — including a lost process — leaves durable evidence that the
     * sender may already hold the money.
     */
    fun markRefundAttempted(at: Instant = Instant.now()): TransferSaga {
        domainRequire(holdCommitted) {
            DomainError.Conflict(
                detail = "cannot record a refund attempt before the sender's hold is committed",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        domainRequire(state == SagaState.COMPENSATING) {
            DomainError.Conflict(
                detail = "cannot record a refund attempt from state $state",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        return copy(refundAttempted = true, updatedAt = at)
    }

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

    /**
     * Records why compensation could not finish, keeping the saga in COMPENSATING.
     *
     * Marking it FAILED here would be a lie and a trap: the sender's funds are still held or
     * debited, and FAILED is terminal, so the only path that could ever return them is closed.
     */
    fun withCompensationFailure(reason: String, at: Instant = Instant.now()): TransferSaga {
        domainRequire(state == SagaState.COMPENSATING) {
            DomainError.Conflict(
                detail = "cannot record a compensation failure from state $state",
                properties = mapOf("sagaId" to id.toString(), "state" to state.name),
            )
        }
        return copy(failureReason = reason, updatedAt = at)
    }

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
        /**
         * CREDIT_APPLIED is absent on purpose: once the recipient holds the money the transfer
         * has economically happened, and "compensating" it would refund the sender without
         * taking anything back — i.e. create money. That case is a business reversal, not a saga
         * compensation.
         */
        private val COMPENSATABLE = setOf(
            SagaState.FUNDS_RESERVED,
            SagaState.LEDGER_POSTED,
            SagaState.COMPENSATING,
        )

        private val FAILABLE = setOf(
            SagaState.INITIATED,
            SagaState.AWAITING_STEP_UP,
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
