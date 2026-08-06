package org.finix.orchestrator.adapter.out.http

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.finix.kernel.domain.Money
import org.finix.orchestrator.application.JournalLineCommand
import org.finix.orchestrator.application.JournalSide
import org.finix.orchestrator.application.usecase.RunTransferSagaUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * The orchestrator's half of the idempotency contract ledger-service enforces.
 *
 * Before this adapter sent a key both assertions below were inverted: the journal post went out
 * bare and ledger-service answered 400 `missing-idempotency-key`. With the account leg already
 * fixed, this was the next step the saga reached and the next place it died.
 *
 * These tests assert *derivation*, not presence. A key that is merely present would satisfy a
 * header check while still being fresh on every retry — the failure mode that double-posts a
 * journal, which on an append-only hash-chained ledger is not something you can quietly undo.
 */
class LedgerHttpClientIdempotencyTest : StringSpec({

    // 1. Every orchestrator -> ledger mutation carries the header.
    "every ledger mutation sends an Idempotency-Key" {
        listOf("forward" to FORWARD_CALL, "reversal" to REVERSAL_CALL).forEach { (name, call) ->
            withClue(name) { capture(call).headers().getFirst(HEADER) shouldNotBe null }
        }
    }

    // 2. Retrying the forward journal reproduces its key.
    "retrying the forward journal produces the same key" {
        keyOf(FORWARD_CALL) shouldBe keyOf(FORWARD_CALL)
        keyOf(FORWARD_CALL) shouldBe "$SAGA:journal"
    }

    // 3. Retrying the reversal reproduces its key. This is the operator-compensation-replay path.
    "retrying the reversal journal produces the same key" {
        keyOf(REVERSAL_CALL) shouldBe keyOf(REVERSAL_CALL)
        keyOf(REVERSAL_CALL) shouldBe "$REVERSAL:journal"
    }

    // 4. Forward and reversal must never share a slot, or compensation would replay the forward
    //    posting's response and the reversal would never be written.
    "forward and reversal use different keys" {
        keyOf(FORWARD_CALL) shouldNotBe keyOf(REVERSAL_CALL)
        listOf(keyOf(FORWARD_CALL), keyOf(REVERSAL_CALL)).toSet() shouldHaveSize 2
    }

    // The reversal id is itself derived, not stored, so its stability is what makes an operator
    // replay safe after a restart. Pinned here so a change to that derivation is caught.
    "the reversal transaction id is deterministic, not random" {
        RunTransferSagaUseCase.reversalTransactionId(SAGA) shouldBe
            RunTransferSagaUseCase.reversalTransactionId(SAGA)
        REVERSAL shouldNotBe SAGA
    }

    // 5. Nothing time- or random-derived participates: two runs separated in time agree exactly,
    //    and both keys are fully explained by identifiers the saga persisted.
    "no random or time-derived value participates in the key" {
        val first = listOf(keyOf(FORWARD_CALL), keyOf(REVERSAL_CALL))
        Thread.sleep(CLOCK_TICK_MS)
        val second = listOf(keyOf(FORWARD_CALL), keyOf(REVERSAL_CALL))
        second shouldBe first
        first shouldBe listOf("$SAGA:journal", "$REVERSAL:journal")
    }

    // A fresh adapter and a fresh WebClient yield the same key: nothing in the derivation is
    // instance state, which is what "stable across process restart" means at this layer.
    "keys survive an adapter rebuild, standing in for a process restart" {
        keyOf(FORWARD_CALL) shouldBe "$SAGA:journal"
        keyOf(REVERSAL_CALL) shouldBe "$REVERSAL:journal"
    }

    // Body stability across a retry matters just as much as the key — ledger-service answers 422
    // idempotency-key-reuse if the fingerprint moves — but it cannot be observed here: a
    // ClientRequest exposes only an opaque BodyInserter. `JournalIdempotencyTest` in
    // ledger-service asserts it where the bytes actually exist.
})

private const val HEADER = "Idempotency-Key"
private const val CLOCK_TICK_MS = 5L

private val SAGA: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
private val REVERSAL: UUID = RunTransferSagaUseCase.reversalTransactionId(SAGA)
private val FROM: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val TO: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
private val AMOUNT: Money = Money.of("100.00")

/** What `RunTransferSagaUseCase` posts for the forward leg. */
private val TRANSFER_LINES = listOf(
    JournalLineCommand(FROM, JournalSide.DEBIT, AMOUNT),
    JournalLineCommand(TO, JournalSide.CREDIT, AMOUNT),
)

/** What it posts when compensating: the same lines with the sides swapped. */
private val REVERSAL_LINES = listOf(
    JournalLineCommand(FROM, JournalSide.CREDIT, AMOUNT),
    JournalLineCommand(TO, JournalSide.DEBIT, AMOUNT),
)

private val FORWARD_CALL: (LedgerHttpClient) -> Unit = { it.postJournal(SAGA, TRANSFER_LINES) }
private val REVERSAL_CALL: (LedgerHttpClient) -> Unit = { it.postJournal(REVERSAL, REVERSAL_LINES) }

private fun keyOf(call: (LedgerHttpClient) -> Unit): String? =
    capture(call).headers().getFirst(HEADER)

/**
 * Runs one adapter call against a stub exchange and returns the request it actually built.
 * A fresh `WebClient` and a fresh adapter per call, so no key can be carried between them.
 */
private fun capture(call: (LedgerHttpClient) -> Unit): ClientRequest {
    lateinit var seen: ClientRequest
    val client = WebClient.builder()
        .baseUrl("http://ledger.invalid")
        .exchangeFunction { request ->
            seen = request
            Mono.just(ClientResponse.create(HttpStatus.OK).build())
        }
        .build()
    call(LedgerHttpClient(client))
    return seen
}
