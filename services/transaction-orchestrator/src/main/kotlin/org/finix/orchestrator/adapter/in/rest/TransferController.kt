package org.finix.orchestrator.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.finix.kernel.domain.Money
import org.finix.orchestrator.application.usecase.CompensateTransferSagaUseCase
import org.finix.orchestrator.application.usecase.GetTransferSagaUseCase
import org.finix.orchestrator.application.usecase.RunTransferSagaUseCase
import org.finix.orchestrator.domain.SagaState
import org.finix.orchestrator.domain.TransferSaga
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateTransferRequest(
    @field:NotNull val fromAccountId: UUID,
    @field:NotNull val toAccountId: UUID,
    @field:NotBlank val amount: String,
)

data class TransferResponse(
    val transferId: UUID,
    val state: SagaState,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: String,
    val holdId: UUID,
    val failureReason: String?,
)

private fun TransferSaga.toResponse() = TransferResponse(
    transferId = id,
    state = state,
    fromAccountId = fromAccountId,
    toAccountId = toAccountId,
    amount = amount.toString(),
    holdId = holdId,
    failureReason = failureReason,
)

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(
    private val runTransfer: RunTransferSagaUseCase,
    private val getTransfer: GetTransferSagaUseCase,
    private val compensateTransfer: CompensateTransferSagaUseCase,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateTransferRequest): TransferResponse {
        val saga = runTransfer.execute(
            fromAccountId = request.fromAccountId,
            toAccountId = request.toAccountId,
            amount = Money.parse(request.amount),
        )
        return saga.toResponse()
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): TransferResponse =
        getTransfer.execute(id).toResponse()

    @PostMapping("/{id}/compensate")
    fun compensate(@PathVariable id: UUID): TransferResponse =
        compensateTransfer.execute(id).toResponse()
}
