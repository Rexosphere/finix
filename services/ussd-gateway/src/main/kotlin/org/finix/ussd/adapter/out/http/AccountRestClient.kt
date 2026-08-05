package org.finix.ussd.adapter.out.http

import io.github.oshai.kotlinlogging.KotlinLogging
import org.finix.kernel.domain.Money
import org.finix.ussd.application.AccountBalanceView
import org.finix.ussd.application.port.AccountClient
import org.finix.ussd.config.AccountClientProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * Reads balances from account-service for the `*334#` menu.
 *
 * Returns null instead of throwing: the use case renders a generic "try again" screen for a
 * missing balance, and a 160-character USSD reply is no place to surface a stack trace.
 */
@Component
class AccountRestClient(
    builder: RestClient.Builder,
    properties: AccountClientProperties,
) : AccountClient {

    private val client: RestClient = builder.baseUrl(properties.baseUrl).build()

    override fun getBalance(accountId: UUID): AccountBalanceView? =
        try {
            client.get()
                .uri("/api/v1/accounts/{id}", accountId)
                .retrieve()
                .body(AccountBalanceResponse::class.java)
                ?.let {
                    AccountBalanceView(
                        accountId = it.id,
                        accountNumber = it.accountNumber,
                        available = it.availableBalance,
                        held = it.heldBalance,
                    )
                }
        } catch (ex: RestClientException) {
            log.warn { "balance lookup for $accountId failed: ${ex.message}" }
            null
        }
}

/** Subset of account-service `AccountResponse` the USSD menu needs. */
internal data class AccountBalanceResponse(
    val id: UUID,
    val accountNumber: String,
    val availableBalance: Money,
    val heldBalance: Money,
)
