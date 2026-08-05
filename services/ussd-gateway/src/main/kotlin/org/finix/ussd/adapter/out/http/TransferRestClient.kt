package org.finix.ussd.adapter.out.http

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.Money
import org.finix.ussd.application.TransferResult
import org.finix.ussd.application.port.TransferClient
import org.finix.ussd.config.OrchestratorClientProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.util.UUID

/**
 * Starts a transfer saga through transaction-orchestrator.
 *
 * Unlike the balance lookup this propagates failures: the caller is told "transfer failed" with
 * a reason, and silently swallowing the error would leave them believing money moved.
 */
@Component
class TransferRestClient(
    builder: RestClient.Builder,
    properties: OrchestratorClientProperties,
) : TransferClient {

    private val client: RestClient = builder.baseUrl(properties.baseUrl).build()

    override fun transfer(fromAccountId: UUID, toAccountId: UUID, amount: Money): TransferResult {
        val response = try {
            client.post()
                .uri("/api/v1/transfers")
                .body(
                    CreateTransferBody(
                        fromAccountId = fromAccountId,
                        toAccountId = toAccountId,
                        amount = amount.toString(),
                    ),
                )
                .retrieve()
                .body(TransferResponseBody::class.java)
        } catch (ex: RestClientException) {
            throw DomainException(
                DomainError.Unavailable("transaction-orchestrator", "transfer failed: ${ex.message}"),
                ex,
            )
        } ?: DomainError.Unavailable("transaction-orchestrator", "empty transfer response").raise()

        return TransferResult(sagaId = response.transferId, status = response.state)
    }
}

/** Mirrors orchestrator `CreateTransferRequest`. */
internal data class CreateTransferBody(
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: String,
)

/** Subset of orchestrator `TransferResponse`. */
internal data class TransferResponseBody(
    val transferId: UUID,
    val state: String,
)
