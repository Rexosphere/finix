package org.finix.orchestrator.adapter.out.http

import com.fasterxml.jackson.annotation.JsonProperty
import org.finix.kernel.domain.DomainError
import org.finix.orchestrator.application.RiskAssessment
import org.finix.orchestrator.application.port.RiskClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * Calls the Python risk-ai-service `POST /v1/score`.
 *
 * That service is a FastAPI app with snake_case Pydantic models, so the wire names are pinned
 * explicitly rather than left to the Kotlin property names.
 */
@Component
class RiskHttpClient(
    @param:Qualifier("riskWebClient") private val client: WebClient,
) : RiskClient {

    @Suppress("LongParameterList")
    override fun scoreTransfer(
        transactionId: String,
        fromAccountId: String,
        toAccountId: String,
        amountMinor: Long,
        currency: String,
        velocity1h: Int,
        newDevice: Boolean,
        offlineVoucher: Boolean,
    ): RiskAssessment {
        val response = callDownstream(DEPENDENCY) {
            client.post()
                .uri("/v1/score")
                .bodyValue(
                    ScoreBody(
                        transactionId = transactionId,
                        fromAccountId = fromAccountId,
                        toAccountId = toAccountId,
                        amountMinor = amountMinor,
                        currency = currency,
                        velocity1h = velocity1h,
                        newDevice = newDevice,
                        offlineVoucher = offlineVoucher,
                    ),
                )
                .retrieve()
                .bodyToMono(ScoreResult::class.java)
                .block(DOWNSTREAM_TIMEOUT)
        } ?: DomainError.Unavailable(DEPENDENCY, "risk scoring returned an empty body").raise()

        return RiskAssessment(
            score = response.score,
            decision = response.decision,
            reasons = response.reasons,
            caseId = response.caseId,
        )
    }

    private companion object {
        const val DEPENDENCY = "risk-ai-service"
    }
}

/** Mirrors risk-ai-service `ScoreRequest`. */
internal data class ScoreBody(
    @param:JsonProperty("transaction_id") @get:JsonProperty("transaction_id")
    val transactionId: String,
    @param:JsonProperty("from_account_id") @get:JsonProperty("from_account_id")
    val fromAccountId: String,
    @param:JsonProperty("to_account_id") @get:JsonProperty("to_account_id")
    val toAccountId: String,
    @param:JsonProperty("amount_minor") @get:JsonProperty("amount_minor")
    val amountMinor: Long,
    val currency: String,
    @param:JsonProperty("velocity_1h") @get:JsonProperty("velocity_1h")
    val velocity1h: Int,
    @param:JsonProperty("new_device") @get:JsonProperty("new_device")
    val newDevice: Boolean,
    @param:JsonProperty("offline_voucher") @get:JsonProperty("offline_voucher")
    val offlineVoucher: Boolean,
)

/** Mirrors risk-ai-service `ScoreResponse`; the model/rules breakdown is not consumed here. */
internal data class ScoreResult(
    val score: Int,
    val decision: String,
    val reasons: List<String> = emptyList(),
    @param:JsonProperty("case_id")
    val caseId: String? = null,
)
