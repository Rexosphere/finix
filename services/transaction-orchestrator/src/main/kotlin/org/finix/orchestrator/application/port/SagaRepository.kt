package org.finix.orchestrator.application.port

import org.finix.orchestrator.domain.TransferSaga
import java.util.UUID

interface SagaRepository {
    fun save(saga: TransferSaga): TransferSaga
    fun findById(id: UUID): TransferSaga?
}
