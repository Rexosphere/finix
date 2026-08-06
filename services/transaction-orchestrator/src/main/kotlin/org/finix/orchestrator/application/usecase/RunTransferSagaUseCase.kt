package org.finix.orchestrator.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.domainRequire
import org.finix.orchestrator.application.JournalLineCommand
import org.finix.orchestrator.application.JournalSide
import org.finix.orchestrator.application.RiskAssessment
import org.finix.orchestrator.application.SagaPersistence
import org.finix.orchestrator.application.port.AccountClient
import org.finix.orchestrator.application.port.LedgerClient
import org.finix.orchestrator.application.port.RiskClient
import org.finix.orchestrator.application.port.SagaRepository
import org.finix.orchestrator.domain.SagaState
import org.finix.orchestrator.domain.TransferSaga
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Runs the internal-transfer saga with risk gating (M7).
 *
 * After INITIATED:
 *  - score &lt; 40 → continue (allow)
 *  - 40–70 → persist AWAITING_STEP_UP and return (client completes MFA then /step-up)
 *  - &gt; 70 → BLOCKED terminal + fraud case opened in risk-ai
 *
 * The gate fails closed: if risk-ai-service is unavailable, times out, or answers with something
 * this service cannot interpret, the saga degrades to AWAITING_STEP_UP rather than transferring
 * unscored. Losing the risk engine therefore costs availability, never money safety.
 */
@Service
class RunTransferSagaUseCase(
    private val persistence: SagaPersistence,
    private val sagas: SagaRepository,
    private val accounts: AccountClient,
    private val ledger: LedgerClient,
    private val risk: RiskClient,
    private val clock: Clock,
) {

    private val log = KotlinLogging.logger {}

    fun execute(
        fromAccountId: UUID,
        toAccountId: UUID,
        amount: Money,
        newDevice: Boolean = false,
        velocity1h: Int = 0,
        offlineVoucher: Boolean = false,
    ): TransferSaga {
        domainRequire(fromAccountId != toAccountId) {
            DomainError.Invalid("from and to accounts must differ")
        }
        domainRequire(amount.isPositive) {
            DomainError.Invalid("transfer amount must be positive", mapOf("amount" to amount.toString()))
        }

        var saga = TransferSaga.initiate(
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amount = amount,
            at = Instant.now(clock),
        )
        saga = persistence.saveInitiated(saga)

        val assessment = try {
            risk.scoreTransfer(
                transactionId = saga.id.toString(),
                fromAccountId = fromAccountId.toString(),
                toAccountId = toAccountId.toString(),
                amountMinor = amount.minorUnits,
                currency = amount.currency.currencyCode,
                velocity1h = velocity1h,
                newDevice = newDevice,
                offlineVoucher = offlineVoucher,
            )
        } catch (ex: Exception) {
            log.warn(ex) { "Risk scoring unavailable for saga ${saga.id}; failing closed to step-up" }
            null
        }

        // The gate is closed by default: only an assessment that explicitly says ALLOW lets a
        // transfer through unchallenged. An outage, a timeout, a malformed body or a decision this
        // version does not understand all degrade to step-up, never to a straight-through transfer.
        val decision = gateDecision(saga, assessment)
        saga = persistence.save(saga.withRisk(assessment?.score, decision, Instant.now(clock)))

        return when (decision) {
            DECISION_BLOCK -> {
                val reason = "risk blocked score=${assessment?.score} case=${assessment?.caseId} reasons=${assessment?.reasons}"
                persistence.saveTerminalFailure(saga.markBlocked(reason, Instant.now(clock)))
            }
            DECISION_STEP_UP -> persistence.save(saga.markAwaitingStepUp(Instant.now(clock)))
            else -> continueAfterRisk(saga)
        }
    }

    /**
     * Completes a saga suspended in [SagaState.AWAITING_STEP_UP] after the caller verified MFA.
     * Demo accepts any non-blank [otpCode]; production would validate WebAuthn/TOTP via identity.
     */
    fun completeStepUp(sagaId: UUID, otpCode: String): TransferSaga {
        domainRequire(otpCode.isNotBlank()) {
            DomainError.Invalid("otpCode required for step-up")
        }
        val saga = sagas.findById(sagaId)
            ?: DomainError.NotFound("TransferSaga", sagaId.toString()).raise()
        domainRequire(saga.state == SagaState.AWAITING_STEP_UP) {
            DomainError.Conflict(
                detail = "saga ${saga.id} is ${saga.state}, expected AWAITING_STEP_UP",
                properties = mapOf("sagaId" to saga.id.toString(), "state" to saga.state.name),
            )
        }
        return continueAfterRisk(saga)
    }

    /**
     * Normalises a risk response into the gate vocabulary, failing closed to [DECISION_STEP_UP]
     * whenever the answer is missing or not one this service recognises.
     */
    private fun gateDecision(saga: TransferSaga, assessment: RiskAssessment?): String {
        if (assessment == null) return DECISION_STEP_UP
        return when (val decision = assessment.decision.trim().lowercase()) {
            DECISION_ALLOW, DECISION_STEP_UP, DECISION_BLOCK -> decision
            else -> {
                log.warn {
                    "Unrecognised risk decision '${assessment.decision}' for saga ${saga.id}; " +
                        "failing closed to step-up"
                }
                DECISION_STEP_UP
            }
        }
    }

    private fun continueAfterRisk(saga: TransferSaga): TransferSaga {
        var current = saga
        try {
            accounts.reserve(current.fromAccountId, current.amount, current.holdId)
            current = persistence.save(current.markReserved(Instant.now(clock)))

            ledger.postJournal(current.id, transferLines(current))
            current = persistence.save(current.markLedgerPosted(Instant.now(clock)))

            accounts.commitHold(current.fromAccountId, current.holdId)
            // Recorded before the credit is attempted: from here the debit is final, and a
            // compensation that does not know that would try to release an uncommittable hold.
            current = persistence.save(current.markHoldCommitted(Instant.now(clock)))
        } catch (ex: Exception) {
            log.warn(ex) { "Transfer saga ${current.id} failed in state ${current.state}: ${ex.message}" }
            return compensate(current, reasonOf(ex), ledgerWasPosted = current.ledgerPosted)
        }

        try {
            accounts.credit(current.toAccountId, current.amount, reference = current.id.toString())
        } catch (ex: Exception) {
            return afterFailedCredit(current, ex)
        }

        // The recipient has the money, so the transfer has economically happened. A bookkeeping
        // failure past this line is raised for reconciliation and never reversed.
        return try {
            current = persistence.save(current.markCreditApplied(Instant.now(clock)))
            persistence.saveCompleted(current.markCompleted(Instant.now(clock)))
        } catch (ex: Exception) {
            log.error(ex) {
                "Transfer ${current.id} credited ${current.amount} to ${current.toAccountId} but could not " +
                    "be recorded as COMPLETED; it needs manual reconciliation and must not be compensated"
            }
            // Deliberately a domain refusal rather than a raw fault: this maps to 409, and
            // IdempotencyFilter records anything below 500 while releasing the key at 500 and
            // above. Letting it escape as a 500 would invite the client's retry to run the whole
            // transfer a second time against the same key, moving the money twice.
            DomainError.Conflict(
                detail = "transfer ${current.id} moved the money but could not be recorded as completed; " +
                    "it is pending reconciliation and must not be resubmitted",
                properties = mapOf(
                    "sagaId" to current.id.toString(),
                    "state" to current.state.name,
                    "reconciliation" to "required",
                ),
            ).raise()
        }
    }

    /**
     * Decides what a failed recipient credit means.
     *
     * A refusal from account-service (any [DomainError] other than [DomainError.Unavailable])
     * proves the credit did not happen: `CreditAccountUseCase` is transactional, so every refusal
     * it can raise — including an optimistic-lock loss — rolls the credit back. The committed
     * debit can therefore be safely returned. Note this is the opposite of a failed *release*,
     * where the same flattened Conflict may mean the hold was already committed, i.e. that
     * something really did happen; see [returnFundsToSender].
     *
     * A timeout or an unreachable dependency proves nothing: the credit may well have landed, and
     * refunding on top of it would create money. That case is frozen by
     * [TransferSaga.markCreditOutcomeUnknown] — no terminal event, no reversal, and no
     * compensation until the two sides are reconciled.
     */
    private fun afterFailedCredit(saga: TransferSaga, ex: Exception): TransferSaga {
        val reason = reasonOf(ex)
        if (ex is DomainException && ex.error !is DomainError.Unavailable) {
            log.warn(ex) { "Credit for saga ${saga.id} was refused; returning the committed debit" }
            // Persisted *before* compensating, because it is the permission to compensate: a saga
            // whose refusal was never recorded is indistinguishable from one whose credit landed.
            val refused = try {
                persistence.save(saga.markCreditRefused(reason, Instant.now(clock)))
            } catch (persistFailure: Exception) {
                log.error(persistFailure) {
                    "Saga ${saga.id} had its credit refused but the refusal could not be recorded; " +
                        "it is frozen until reconciled rather than reversed without proof"
                }
                reconciliationRequired(saga, "the refused credit could not be recorded")
            }
            return compensate(refused, reason, ledgerWasPosted = refused.ledgerPosted)
        }
        log.error(ex) {
            "Credit outcome for saga ${saga.id} is unknown; freezing it for reconciliation rather " +
                "than risking a refund on top of a credit that may have landed"
        }
        return try {
            persistence.save(
                saga.markCreditOutcomeUnknown("$reason (credit outcome unknown)", Instant.now(clock)),
            )
        } catch (persistFailure: Exception) {
            log.error(persistFailure) { "Saga ${saga.id} could not be frozen after an unknown credit outcome" }
            reconciliationRequired(saga, "the unknown credit outcome could not be recorded")
        }
    }

    fun compensate(
        saga: TransferSaga,
        reason: String,
        ledgerWasPosted: Boolean = saga.ledgerPosted,
    ): TransferSaga {
        // The single choke point for every compensation, automatic or operator-triggered. While
        // the recipient's credit is unaccounted for, returning the sender's money could pay out
        // an amount the recipient already holds, so nothing may reverse it until it is reconciled.
        domainRequire(!saga.creditOutcomeUnknown) {
            frozen(saga, "has an unresolved credit outcome")
        }
        // A refund has already been issued, or was issued and never confirmed. `credit` is not
        // idempotent downstream, so a second attempt would pay the sender twice; there is nothing
        // safe left to do automatically.
        domainRequire(!saga.refundAttempted) {
            frozen(saga, "has already had a compensating refund attempted")
        }
        // The recipient credit was attempted — `holdCommitted` is written immediately before it —
        // and no definitive refusal was ever recorded. The money may be with the recipient, so a
        // reversal here would be a guess. Only proof, not the absence of a success, permits one.
        domainRequire(!(saga.state == SagaState.LEDGER_POSTED && saga.holdCommitted && !saga.creditRefused)) {
            frozen(saga, "has no recorded recipient-credit outcome after a committed hold")
        }
        if (saga.state == SagaState.INITIATED || saga.state == SagaState.AWAITING_STEP_UP) {
            return persistence.saveTerminalFailure(saga.markFailed(reason, Instant.now(clock)))
        }
        if (saga.state == SagaState.BLOCKED) {
            DomainError.Conflict(
                detail = "saga ${saga.id} is BLOCKED and cannot be compensated",
                properties = mapOf("sagaId" to saga.id.toString(), "state" to saga.state.name),
            ).raise()
        }
        if (saga.state in TERMINAL) {
            DomainError.Conflict(
                detail = "saga ${saga.id} is terminal (${saga.state}) and cannot be compensated",
                properties = mapOf("sagaId" to saga.id.toString(), "state" to saga.state.name),
            ).raise()
        }

        val compensating = if (saga.state == SagaState.COMPENSATING) {
            saga
        } else {
            persistence.save(saga.beginCompensate(reason, Instant.now(clock)))
        }

        // Claim the refund before anything else can fail. Everything past this point either
        // returns the funds or leaves durable evidence that it may have; a crash before it costs
        // only an automatic retry, whereas a crash after the credit would cost the amount again.
        val settled = try {
            if (compensating.holdCommitted) {
                persistence.save(compensating.markRefundAttempted(Instant.now(clock)))
            } else {
                compensating
            }
        } catch (ex: Exception) {
            log.error(ex) { "Refund guard for saga ${compensating.id} could not be recorded; not refunding" }
            reconciliationRequired(compensating, "the refund guard could not be recorded")
        }

        return try {
            if (ledgerWasPosted) {
                try {
                    ledger.postJournal(reversalTransactionId(settled.id), reversalLines(settled))
                } catch (ex: Exception) {
                    log.warn(ex) {
                        "Ledger reversal for saga ${settled.id} failed; continuing with hold release"
                    }
                }
            }
            returnFundsToSender(settled)
            persistence.saveTerminalFailure(settled.markCompensated(Instant.now(clock)))
        } catch (ex: UnknownRefundOutcome) {
            // Same rule as an unresolved recipient credit: the refund is a non-idempotent credit,
            // so an unknown outcome is frozen rather than retried. Reissuing it could pay the
            // sender money they already have.
            log.error(ex.failure) {
                "Refund outcome for saga ${settled.id} is unknown; freezing it for reconciliation " +
                    "rather than risking a second credit to the sender"
            }
            persistence.save(
                settled.markCreditOutcomeUnknown(
                    reason = "$reason; refund outcome unknown: ${reasonOf(ex.failure)}",
                    at = Instant.now(clock),
                ),
            )
        } catch (ex: Exception) {
            // Left in COMPENSATING, not FAILED: the sender's money is still held or debited, and a
            // terminal state would remove the only route by which it can be returned. Whether a
            // retry may actually re-issue anything is decided by the guards at the top of this
            // method — `refundAttempted` is already durable by now if a refund was in play.
            log.error(ex) { "Compensation failed for saga ${settled.id}; leaving it recoverable" }
            persistence.save(
                settled.withCompensationFailure(
                    reason = "$reason; compensation failed: ${reasonOf(ex)}",
                    at = Instant.now(clock),
                ),
            )
        }
    }

    /**
     * Refuses to act because the saga's money position cannot be established from what is durable.
     *
     * A [DomainError.Conflict] rather than a raw fault on purpose: it maps to 409, and
     * `IdempotencyFilter` records anything below 500 while releasing the key at 500 and above.
     * A 500 here would invite the client's retry to run the whole transfer again.
     */
    private fun reconciliationRequired(saga: TransferSaga, why: String): Nothing =
        frozen(saga, why).raise()

    private fun frozen(saga: TransferSaga, why: String): DomainError.Conflict = DomainError.Conflict(
        detail = "saga ${saga.id} $why and cannot be compensated until it is reconciled",
        properties = mapOf(
            "sagaId" to saga.id.toString(),
            "state" to saga.state.name,
            "reconciliation" to "required",
        ),
    )

    /**
     * Undoes the sender's debit with the only operation that is provably valid.
     *
     * A refund is issued **only** when this saga itself persisted [TransferSaga.holdCommitted] —
     * that is first-hand knowledge that the debit is final and that a release would be refused.
     *
     * Otherwise the hold is believed OPEN and is released. If that release does not clearly
     * succeed, nothing is inferred from the error: `callDownstream` flattens 400, 404 and 409
     * alike, and a 409 is as likely to be a retryable optimistic-lock loss as a committed hold.
     * Guessing "committed" there would pay the sender for a debit that may never have happened,
     * so the failure is allowed to propagate and the saga stays recoverable instead.
     */
    private fun returnFundsToSender(saga: TransferSaga) {
        if (saga.holdCommitted) {
            refundSender(saga)
            return
        }
        accounts.releaseHold(saga.fromAccountId, saga.holdId)
    }

    /**
     * Issues the compensating credit, classifying its failure the same way [afterFailedCredit]
     * classifies the recipient's.
     *
     * The refund is itself a non-idempotent credit, so no outcome may be retried automatically:
     * `refundAttempted` is already durable before this runs, and the guard at the top of
     * [compensate] refuses every later attempt. A definitive refusal genuinely changed nothing,
     * but nothing durable distinguishes it from a refusal raised after the credit was applied, so
     * it is reconciled by hand rather than replayed.
     */
    private fun refundSender(saga: TransferSaga) {
        try {
            accounts.credit(saga.fromAccountId, saga.amount, reference = refundReference(saga.id))
        } catch (ex: DomainException) {
            // A definitive refusal (anything that is not Unavailable) genuinely changed nothing,
            // so it propagates unchanged. Unavailable is indistinguishable from a refusal raised
            // after the credit landed, so it joins the ambiguous case below.
            if (ex.error !is DomainError.Unavailable) throw ex
            throw UnknownRefundOutcome(ex)
        } catch (ex: Exception) {
            throw UnknownRefundOutcome(ex)
        }
    }

    /** The refund credit may or may not have reached the sender; nothing may retry it. */
    private class UnknownRefundOutcome(val failure: Exception) : RuntimeException(failure)

    private fun transferLines(saga: TransferSaga): List<JournalLineCommand> = listOf(
        JournalLineCommand(saga.fromAccountId, JournalSide.DEBIT, saga.amount),
        JournalLineCommand(saga.toAccountId, JournalSide.CREDIT, saga.amount),
    )

    private fun reversalLines(saga: TransferSaga): List<JournalLineCommand> = listOf(
        JournalLineCommand(saga.fromAccountId, JournalSide.CREDIT, saga.amount),
        JournalLineCommand(saga.toAccountId, JournalSide.DEBIT, saga.amount),
    )

    companion object {
        /** Gate vocabulary shared with risk-ai-service `Decision` (`allow` | `step_up` | `block`). */
        const val DECISION_ALLOW = "allow"
        const val DECISION_STEP_UP = "step_up"
        const val DECISION_BLOCK = "block"

        private val TERMINAL = setOf(
            SagaState.COMPLETED,
            SagaState.COMPENSATED,
            SagaState.FAILED,
            SagaState.BLOCKED,
        )

        fun reversalTransactionId(sagaId: UUID): UUID =
            UUID.nameUUIDFromBytes("reversal:$sagaId".toByteArray())

        /** Correlates the compensating credit with the saga it undoes. */
        fun refundReference(sagaId: UUID): String = "refund:$sagaId"

        fun reasonOf(ex: Exception): String = when (ex) {
            is DomainException -> ex.error.detail
            else -> ex.message ?: ex.javaClass.simpleName
        }
    }
}
