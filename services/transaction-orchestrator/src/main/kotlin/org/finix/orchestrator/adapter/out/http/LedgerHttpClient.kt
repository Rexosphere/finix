package org.finix.orchestrator.adapter.out.http

import org.finix.orchestrator.application.JournalLineCommand
import org.finix.orchestrator.application.port.LedgerClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/** Calls ledger-service over the REST contract in `LedgerController`. */
@Component
class LedgerHttpClient(
    @param:Qualifier("ledgerWebClient") private val client: WebClient,
) : LedgerClient {

    override fun postJournal(transactionId: UUID, lines: List<JournalLineCommand>) {
        val body = PostJournalBody(
            transactionId = transactionId,
            lines = lines.map {
                JournalLineBody(
                    accountId = it.accountId,
                    side = it.side.name,
                    amount = it.amount.toString(),
                )
            },
        )
        callDownstream(DEPENDENCY) {
            client.post()
                .uri("/api/v1/ledger/journals")
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    private companion object {
        const val DEPENDENCY = "ledger-service"
    }
}

/** Mirrors `PostJournalRequest`. */
internal data class PostJournalBody(
    val transactionId: UUID,
    val lines: List<JournalLineBody>,
)

/** Mirrors `JournalLineRequest` — amount is the canonical `"LKR 100.00"` string it parses. */
internal data class JournalLineBody(
    val accountId: UUID,
    val side: String,
    val amount: String,
)
