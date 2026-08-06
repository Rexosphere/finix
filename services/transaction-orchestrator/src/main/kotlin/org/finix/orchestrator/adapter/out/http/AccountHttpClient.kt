package org.finix.orchestrator.adapter.out.http

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.domainRequire
import org.finix.kernel.idempotency.IdempotencyFilter
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
 *
 * **Idempotency.** account-service runs `IdempotencyFilter`, which answers any keyless POST with
 * 400 `missing-idempotency-key`, so every mutation below must carry a key. The keys are *derived*
 * from identifiers the saga already persisted before the first downstream call — never generated
 * here — because the filter's contract is "the same logical operation retried must present the
 * same key". A `UUID.randomUUID()` at request time would satisfy the header check and defeat the
 * entire mechanism: each retry would look like a new operation and could credit an account twice.
 *
 * `holdId` and the credit `reference` (the saga id) are columns of `orchestrator_saga`, written by
 * `saveInitiated` before any call goes out. Deriving from them makes the key stable across a
 * timeout retry, an orchestrator restart, and an operator replaying compensation.
 *
 * The suffix is not decoration. The filter stores by key alone while fingerprinting method + path
 * + body, so one key reused across two routes returns 422 `idempotency-key-reuse`, not a replay.
 * Distinct suffixes are what keep the operations in separate key slots.
 */
@Component
class AccountHttpClient(
    @param:Qualifier("accountWebClient") private val client: WebClient,
) : AccountClient {

    override fun reserve(accountId: UUID, amount: Money, holdId: UUID) {
        callDownstream(DEPENDENCY) {
            client.post()
                .uri("/api/v1/accounts/{id}/reserves", accountId)
                .header(IdempotencyFilter.HEADER, idempotencyKey(holdId, RESERVE))
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
                .header(IdempotencyFilter.HEADER, idempotencyKey(holdId, COMMIT))
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    override fun releaseHold(accountId: UUID, holdId: UUID) {
        callDownstream(DEPENDENCY) {
            client.post()
                .uri("/api/v1/accounts/{id}/reserves/{holdId}/release", accountId, holdId)
                .header(IdempotencyFilter.HEADER, idempotencyKey(holdId, RELEASE))
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    /**
     * Credits an account. [reference] is the saga's own durable reference — the recipient leg
     * passes the saga id, and any compensating refund leg passes a reference distinct from it,
     * so the two produce different keys without this adapter needing to know which it is.
     */
    override fun credit(accountId: UUID, amount: Money, reference: String) {
        callDownstream(DEPENDENCY) {
            client.post()
                .uri("/api/v1/accounts/{id}/credits", accountId)
                .header(IdempotencyFilter.HEADER, idempotencyKey(reference, CREDIT))
                .bodyValue(CreditBody(amount = amount, reference = reference))
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    private companion object {
        const val DEPENDENCY = "account-service"

        const val RESERVE = "reserve"
        const val COMMIT = "commit"
        const val RELEASE = "release"
        const val CREDIT = "credit"

        /**
         * Builds `<durable-id>:<operation>`.
         *
         * A blank [durableId] is rejected rather than allowed to produce the bare key `":credit"`,
         * which every saga would then share — the second transfer would replay the first one's
         * response and silently skip a credit. That is a worse failure than the 400 this fixes,
         * so it fails fast instead.
         */
        fun idempotencyKey(durableId: Any, operation: String): String {
            val id = durableId.toString().trim()
            domainRequire(id.isNotEmpty()) {
                DomainError.Invalid(
                    "cannot derive an idempotency key for '$operation' without a durable reference",
                    mapOf("operation" to operation),
                )
            }
            return "$id:$operation"
        }
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
