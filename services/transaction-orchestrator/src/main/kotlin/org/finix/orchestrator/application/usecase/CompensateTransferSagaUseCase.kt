package org.finix.orchestrator.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.orchestrator.application.port.SagaRepository
import org.finix.orchestrator.domain.SagaState
import org.finix.orchestrator.domain.TransferSaga
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GetTransferSagaUseCase(
    private val sagas: SagaRepository,
) {
    fun execute(id: UUID): TransferSaga =
        sagas.findById(id)
            ?: DomainError.NotFound("transfer", id.toString()).raise()
}

/**
 * Admin/chaos endpoint: re-run compensation for a saga stuck after reserve (or mid-compensate).
 */
@Service
class CompensateTransferSagaUseCase(
    private val sagas: SagaRepository,
    private val runTransfer: RunTransferSagaUseCase,
) {
    fun execute(id: UUID): TransferSaga {
        val saga = sagas.findById(id)
            ?: DomainError.NotFound("transfer", id.toString()).raise()

        // CREDIT_APPLIED is not listed: the recipient already has the money, so there is nothing
        // left that compensation can take back — undoing it would only duplicate the amount.
        when (saga.state) {
            SagaState.FUNDS_RESERVED,
            SagaState.LEDGER_POSTED,
            SagaState.COMPENSATING,
            -> Unit
            else -> DomainError.Conflict(
                detail = "saga ${saga.id} in state ${saga.state} is not compensatable",
                properties = mapOf("sagaId" to id.toString(), "state" to saga.state.name),
            ).raise()
        }

        return runTransfer.compensate(
            saga = saga,
            reason = saga.failureReason ?: "admin compensation replay",
            ledgerWasPosted = saga.ledgerPosted,
        )
    }
}
