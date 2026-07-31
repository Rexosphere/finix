package org.finix.orchestrator.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.domainRequire
import org.finix.orchestrator.application.JournalLineCommand
import org.finix.orchestrator.application.JournalSide
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

        if (assessment != null) {
            saga = persistence.save(
                saga.withRisk(assessment.score, assessment.decision, Instant.now(clock)),
            )
            when (assessment.decision.lowercase()) {
                "block" -> {
                    val reason = "risk blocked score=${assessment.score} case=${assessment.caseId} reasons=${assessment.reasons}"
                    return persistence.saveTerminalFailure(saga.markBlocked(reason, Instant.now(clock)))
                }
                "step_up" -> {
                    return persistence.save(saga.markAwaitingStepUp(Instant.now(clock)))
                }
            }
        }

        return continueAfterRisk(saga)
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

    private fun continueAfterRisk(saga: TransferSaga): TransferSaga {
        var current = saga
        return try {
            accounts.reserve(current.fromAccountId, current.amount, current.holdId)
            current = persistence.save(current.markReserved(Instant.now(clock)))

            ledger.postJournal(current.id, transferLines(current))
            current = persistence.save(current.markLedgerPosted(Instant.now(clock)))

            accounts.commitHold(current.fromAccountId, current.holdId)
            accounts.credit(current.toAccountId, current.amount, reference = current.id.toString())
            current = current
                .markCreditApplied(Instant.now(clock))
                .markCompleted(Instant.now(clock))
            persistence.saveCompleted(current)
        } catch (ex: Exception) {
            log.warn(ex) { "Transfer saga ${current.id} failed in state ${current.state}: ${ex.message}" }
            compensate(current, reasonOf(ex), ledgerWasPosted = current.ledgerPosted)
        }
    }

    fun compensate(
        saga: TransferSaga,
        reason: String,
        ledgerWasPosted: Boolean = saga.ledgerPosted,
    ): TransferSaga {
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

        return try {
            if (ledgerWasPosted) {
                try {
                    ledger.postJournal(reversalTransactionId(compensating.id), reversalLines(compensating))
                } catch (ex: Exception) {
                    log.warn(ex) {
                        "Ledger reversal for saga ${compensating.id} failed; continuing with hold release"
                    }
                }
            }
            accounts.releaseHold(compensating.fromAccountId, compensating.holdId)
            persistence.saveTerminalFailure(compensating.markCompensated(Instant.now(clock)))
        } catch (ex: Exception) {
            log.error(ex) { "Compensation failed for saga ${compensating.id}" }
            persistence.saveTerminalFailure(
                compensating.markFailed(
                    reason = "$reason; compensation failed: ${reasonOf(ex)}",
                    at = Instant.now(clock),
                ),
            )
        }
    }

    private fun transferLines(saga: TransferSaga): List<JournalLineCommand> = listOf(
        JournalLineCommand(saga.fromAccountId, JournalSide.DEBIT, saga.amount),
        JournalLineCommand(saga.toAccountId, JournalSide.CREDIT, saga.amount),
    )

    private fun reversalLines(saga: TransferSaga): List<JournalLineCommand> = listOf(
        JournalLineCommand(saga.fromAccountId, JournalSide.CREDIT, saga.amount),
        JournalLineCommand(saga.toAccountId, JournalSide.DEBIT, saga.amount),
    )

    companion object {
        private val TERMINAL = setOf(
            SagaState.COMPLETED,
            SagaState.COMPENSATED,
            SagaState.FAILED,
            SagaState.BLOCKED,
        )

        fun reversalTransactionId(sagaId: UUID): UUID =
            UUID.nameUUIDFromBytes("reversal:$sagaId".toByteArray())

        fun reasonOf(ex: Exception): String = when (ex) {
            is DomainException -> ex.error.detail
            else -> ex.message ?: ex.javaClass.simpleName
        }
    }
}
