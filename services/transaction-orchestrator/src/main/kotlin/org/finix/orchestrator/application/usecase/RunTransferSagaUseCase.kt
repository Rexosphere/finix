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
import org.finix.orchestrator.domain.SagaState
import org.finix.orchestrator.domain.TransferSaga
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Runs the internal-transfer saga end-to-end.
 *
 * Happy path:
 *  1. Persist INITIATED + outbox `transaction.initiated`
 *  2. Reserve funds on the source account
 *  3. Persist FUNDS_RESERVED
 *  4. Post balanced DEBIT/CREDIT journal
 *  5. Persist LEDGER_POSTED
 *  6. Commit hold + credit destination
 *  7. Persist COMPLETED + outbox `transaction.committed`
 *
 * On failure after reserve: compensate (release hold; reverse journal if posted) and emit
 * `transaction.failed`.
 */
@Service
class RunTransferSagaUseCase(
    private val persistence: SagaPersistence,
    private val accounts: AccountClient,
    private val ledger: LedgerClient,
    private val clock: Clock,
) {

    private val log = KotlinLogging.logger {}

    fun execute(fromAccountId: UUID, toAccountId: UUID, amount: Money): TransferSaga {
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

        return try {
            accounts.reserve(saga.fromAccountId, saga.amount, saga.holdId)
            saga = persistence.save(saga.markReserved(Instant.now(clock)))

            ledger.postJournal(saga.id, transferLines(saga))
            saga = persistence.save(saga.markLedgerPosted(Instant.now(clock)))

            accounts.commitHold(saga.fromAccountId, saga.holdId)
            accounts.credit(saga.toAccountId, saga.amount, reference = saga.id.toString())
            saga = saga
                .markCreditApplied(Instant.now(clock))
                .markCompleted(Instant.now(clock))
            persistence.saveCompleted(saga)
        } catch (ex: Exception) {
            log.warn(ex) { "Transfer saga ${saga.id} failed in state ${saga.state}: ${ex.message}" }
            compensate(saga, reasonOf(ex), ledgerWasPosted = saga.ledgerPosted)
        }
    }

    /**
     * Compensates a non-terminal saga. [ledgerWasPosted] defaults to [TransferSaga.ledgerPosted]
     * so admin replay after a crash mid-compensate still reverses only when a forward journal ran.
     */
    fun compensate(
        saga: TransferSaga,
        reason: String,
        ledgerWasPosted: Boolean = saga.ledgerPosted,
    ): TransferSaga {
        if (saga.state == SagaState.INITIATED) {
            return persistence.saveTerminalFailure(saga.markFailed(reason, Instant.now(clock)))
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
                    // Append-only ledger + deterministic reversal id: a replay may already have reversed.
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
        )

        fun reversalTransactionId(sagaId: UUID): UUID =
            UUID.nameUUIDFromBytes("reversal:$sagaId".toByteArray())

        fun reasonOf(ex: Exception): String = when (ex) {
            is DomainException -> ex.error.detail
            else -> ex.message ?: ex.javaClass.simpleName
        }
    }
}
