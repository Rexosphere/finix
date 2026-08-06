package org.finix.orchestrator.adapter.out.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.Money
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * The orchestrator's half of the idempotency contract account-service enforces.
 *
 * Before this adapter sent a key, all five assertions in the first block were the *opposite* of
 * what they are now: every mutation went out bare and account-service answered 400
 * `missing-idempotency-key`, so the saga compensated on its very first downstream call.
 *
 * These tests assert derivation, not just presence. A key that is merely present would pass a
 * header check while still being a fresh random value on every retry — which is the failure mode
 * that actually loses money, because the filter would treat each retry as a new credit.
 */
class AccountHttpClientIdempotencyTest : StringSpec({

    // 1. Every account mutation carries the header.
    "every account mutation sends an Idempotency-Key" {
        val keys = allOperations().map { (name, call) ->
            val key = capture(call).headers().getFirst(HEADER)
            withClue(name) { key shouldNotBe null }
            key!!
        }
        keys shouldHaveSize OPERATION_COUNT
        keys.forEach { it.isNotBlank() shouldBe true }
    }

    // 2-5. Retrying the same logical operation reproduces the same key.
    "retrying the same reserve produces the same key" {
        keyOf { it.reserve(ACCOUNT, AMOUNT, HOLD) } shouldBe keyOf { it.reserve(ACCOUNT, AMOUNT, HOLD) }
    }

    "retrying the same commit produces the same key" {
        keyOf { it.commitHold(ACCOUNT, HOLD) } shouldBe keyOf { it.commitHold(ACCOUNT, HOLD) }
    }

    "retrying the same release produces the same key" {
        keyOf { it.releaseHold(ACCOUNT, HOLD) } shouldBe keyOf { it.releaseHold(ACCOUNT, HOLD) }
    }

    "retrying the same recipient credit produces the same key" {
        keyOf { it.credit(ACCOUNT, AMOUNT, RECIPIENT_REFERENCE) } shouldBe
            keyOf { it.credit(ACCOUNT, AMOUNT, RECIPIENT_REFERENCE) }
    }

    "retrying the same refund credit produces the same key" {
        keyOf { it.credit(ACCOUNT, AMOUNT, REFUND_REFERENCE) } shouldBe
            keyOf { it.credit(ACCOUNT, AMOUNT, REFUND_REFERENCE) }
    }

    // A fresh adapter and a fresh WebClient must not change the key: this is what "stable across
    // process restart" means at this layer, since nothing in the derivation is instance state.
    "keys survive an adapter rebuild, standing in for a process restart" {
        keyOf { it.reserve(ACCOUNT, AMOUNT, HOLD) } shouldBe "$HOLD:reserve"
        keyOf { it.credit(ACCOUNT, AMOUNT, RECIPIENT_REFERENCE) } shouldBe "$RECIPIENT_REFERENCE:credit"
    }

    // 6. The five logical operations occupy five distinct key slots. The filter stores by key
    //    alone, so a shared key across two routes is a 422, not a replay.
    "reserve, commit, release, recipient credit and refund do not share a key" {
        allOperations().map { (_, call) -> capture(call).headers().getFirst(HEADER) }
            .toSet() shouldHaveSize OPERATION_COUNT
    }

    // 7. Nothing random or time-derived participates. Two runs separated in time agree exactly,
    //    and every key is fully explained by identifiers the saga persisted.
    "no random or time-derived value participates in the key" {
        val first = allOperations().map { (_, call) -> capture(call).headers().getFirst(HEADER) }
        Thread.sleep(CLOCK_TICK_MS)
        val second = allOperations().map { (_, call) -> capture(call).headers().getFirst(HEADER) }
        second shouldBe first
        first shouldBe listOf(
            "$HOLD:reserve",
            "$HOLD:commit",
            "$HOLD:release",
            "$RECIPIENT_REFERENCE:credit",
            "$REFUND_REFERENCE:credit",
        )
    }

    // A blank reference would derive ":credit", a key every saga would share — the second
    // transfer would replay the first one's response and skip a real credit.
    "a blank credit reference is refused rather than deriving a shared key" {
        shouldThrow<DomainException> {
            capture { it.credit(ACCOUNT, AMOUNT, "   ") }
        }.error.detail shouldContain "durable reference"
    }
})

private const val HEADER = "Idempotency-Key"
private const val OPERATION_COUNT = 5
private const val CLOCK_TICK_MS = 5L

private val ACCOUNT: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val HOLD: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
private val SAGA: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
private val AMOUNT: Money = Money.of("100.00")

/** What `RunTransferSagaUseCase` passes for the recipient leg: the saga id. */
private val RECIPIENT_REFERENCE: String = SAGA.toString()

/**
 * A refund leg's reference. No such call site exists in the saga today — compensation releases
 * the hold rather than crediting back — so this asserts the derivation stays collision-free if
 * one is added, without this test pretending the saga already does it.
 */
private val REFUND_REFERENCE: String = "refund:$SAGA"

private fun allOperations(): List<Pair<String, (AccountHttpClient) -> Unit>> = listOf(
    "reserve" to { c: AccountHttpClient -> c.reserve(ACCOUNT, AMOUNT, HOLD) },
    "commit" to { c: AccountHttpClient -> c.commitHold(ACCOUNT, HOLD) },
    "release" to { c: AccountHttpClient -> c.releaseHold(ACCOUNT, HOLD) },
    "recipient-credit" to { c: AccountHttpClient -> c.credit(ACCOUNT, AMOUNT, RECIPIENT_REFERENCE) },
    "refund-credit" to { c: AccountHttpClient -> c.credit(ACCOUNT, AMOUNT, REFUND_REFERENCE) },
)

private fun keyOf(call: (AccountHttpClient) -> Unit): String? =
    capture(call).headers().getFirst(HEADER)

/**
 * Runs one adapter call against a stub exchange and returns the request it actually built.
 * A fresh `WebClient` and a fresh adapter per call, so no key can be carried over between them.
 */
private fun capture(call: (AccountHttpClient) -> Unit): ClientRequest {
    lateinit var seen: ClientRequest
    val client = WebClient.builder()
        .baseUrl("http://account.invalid")
        .exchangeFunction { request ->
            seen = request
            Mono.just(ClientResponse.create(HttpStatus.OK).build())
        }
        .build()
    call(AccountHttpClient(client))
    return seen
}
