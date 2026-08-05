package org.finix.orchestrator.adapter.out.http

import org.finix.kernel.domain.Money
import org.finix.orchestrator.application.port.AccountClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/**
 * Calls account-service over the REST contract in `AccountController`.
 *
 * Blocking on the reactive client is deliberate: the saga is a sequential, virtual-thread-carried
 * workflow, and modelling it reactively would buy nothing but a harder-to-read compensation path.
 */
@Component
class AccountHttpClient(
    @param:Qualifier("accountWebClient") private val client: WebClient,
) : AccountClient {

    override fun reserve(accountId: UUID, amount: Money, holdId: UUID) {
        callDownstream(DEPENDENCY) {
            client.post()
                .uri("/api/v1/accounts/{id}/reserves", accountId)
                .bodyValue(ReserveFundsBody(amount = amount, holdId = holdId))
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    override fun commitHold(accountId: UUID, holdId: UUID) {
        callDownstream(DEPENDENCY) {
            client.post()
                .uri("/api/v1/accounts/{id}/reserves/{holdId}/commit", accountId, holdId)
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    override fun releaseHold(accountId: UUID, holdId: UUID) {
        callDownstream(DEPENDENCY) {
            client.post()
                .uri("/api/v1/accounts/{id}/reserves/{holdId}/release", accountId, holdId)
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    override fun credit(accountId: UUID, amount: Money, reference: String) {
        callDownstream(DEPENDENCY) {
            client.post()
                .uri("/api/v1/accounts/{id}/credits", accountId)
                .bodyValue(CreditBody(amount = amount, reference = reference))
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    private companion object {
        const val DEPENDENCY = "account-service"
    }
}

/** Mirrors `ReserveFundsRequest`; [Money] serialises to the canonical `"LKR 100.00"` form. */
internal data class ReserveFundsBody(
    val amount: Money,
    val holdId: UUID,
)

/** Mirrors `CreditAccountRequest`. */
internal data class CreditBody(
    val amount: Money,
    val reference: String?,
)
