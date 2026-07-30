package org.finix.orchestrator.application

import org.finix.orchestrator.application.port.OutboxPort
import org.finix.orchestrator.application.port.SagaRepository
import org.finix.orchestrator.domain.TransferSaga
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Short transactional writes for saga state + outbox.
 *
 * Kept as its own bean so [RunTransferSagaUseCase] can call it across HTTP steps without
 * Spring self-invocation swallowing `@Transactional`.
 */
@Component
class SagaPersistence(
    private val sagas: SagaRepository,
    private val outbox: OutboxPort,
) {
    @Transactional
    fun saveInitiated(saga: TransferSaga): TransferSaga {
        val saved = sagas.save(saga)
        outbox.appendInitiated(saved)
        return saved
    }

    @Transactional
    fun save(saga: TransferSaga): TransferSaga = sagas.save(saga)

    @Transactional
    fun saveCompleted(saga: TransferSaga): TransferSaga {
        val saved = sagas.save(saga)
        outbox.appendCommitted(saved)
        return saved
    }

    @Transactional
    fun saveTerminalFailure(saga: TransferSaga): TransferSaga {
        val saved = sagas.save(saga)
        outbox.appendFailed(saved)
        return saved
    }
}
