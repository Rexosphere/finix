package org.finix.orchestrator.adapter.out.http

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.domainRequire
import org.finix.kernel.idempotency.IdempotencyFilter
import org.finix.orchestrator.application.JournalLineCommand
import org.finix.orchestrator.application.port.LedgerClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/**
 * Calls ledger-service over the REST contract in `LedgerController`.
 *
 * **Idempotency.** ledger-service declares no `finix.idempotency` block, and the filter registers
 * with `matchIfMissing = true`, so `POST /api/v1/ledger/journals` is enforced there exactly as on
 * account-service: a keyless post is answered 400 `missing-idempotency-key`. The key is *derived*
 * from the `transactionId` the saga already persisted — never generated here. Generating one per
 * request would pass the header check and defeat the mechanism, because each retry would look
 * like a new journal and could double-post the transfer.
 *
 * `transactionId` is the ledger's own identity for the journal, which makes it the right scope:
 * the forward leg passes the saga id and the reversal passes
 * `RunTransferSagaUseCase.reversalTransactionId(sagaId)` — a `nameUUIDFromBytes` digest of
 * `"reversal:<sagaId>"`, so it is both deterministic and distinct from the forward id. The two
 * legs therefore cannot collide, and neither depends on a clock or a random source.
 *
 * The `:journal` suffix matches the shape used for account mutations and keeps this key from ever
 * colliding with one minted for a different ledger operation on the same transaction id.
 */
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
                .header(IdempotencyFilter.HEADER, idempotencyKey(transactionId))
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block(DOWNSTREAM_TIMEOUT)
        }
    }

    private companion object {
        const val DEPENDENCY = "ledger-service"
        const val JOURNAL = "journal"

        /**
         * Builds `<transactionId>:journal`.
         *
         * A blank id is refused rather than allowed to derive the bare key `":journal"`, which
         * every saga would share — the second transfer would replay the first one's response and
         * silently skip a real journal posting. That is worse than the 400 this fixes.
         */
        fun idempotencyKey(transactionId: UUID): String {
            val id = transactionId.toString().trim()
            domainRequire(id.isNotEmpty()) {
                DomainError.Invalid("cannot derive a ledger idempotency key without a transaction id")
            }
            return "$id:$JOURNAL"
        }
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
