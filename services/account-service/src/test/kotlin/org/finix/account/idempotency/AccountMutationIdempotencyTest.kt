package org.finix.account.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.finix.kernel.idempotency.IdempotencyFilter
import org.finix.kernel.idempotency.IdempotencyProperties
import org.finix.kernel.idempotency.InMemoryIdempotencyStore
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The account-service half of the contract the orchestrator now satisfies.
 *
 * Exercises the real [IdempotencyFilter] over the real account routes rather than a full Spring
 * context: the filter is the entire mechanism under test, and booting the application would add
 * Postgres and Redis without testing anything more. `CreditAccountUseCase` is deliberately *not*
 * involved — a stub chain counts executions, which is the property that matters, because
 * `Account.credit` adds unconditionally and would happily double-credit if the filter let a
 * retry through.
 */
class AccountMutationIdempotencyTest : StringSpec({

    // 8. The header the orchestrator now sends is accepted; the keyless request it used to send
    //    is not. Both halves are asserted so the fix cannot be mistaken for the filter softening.
    "a keyless account mutation is refused with missing-idempotency-key" {
        val response = MockHttpServletResponse()
        val executions = AtomicInteger()

        newFilter().doFilter(post(CREDIT_PATH, body = CREDIT_BODY), response, countingChain(executions))

        response.status shouldBe HttpStatus.BAD_REQUEST.value()
        response.contentAsString shouldContain "missing-idempotency-key"
        executions.get() shouldBe 0
    }

    "the derived Idempotency-Key is accepted and reaches the handler" {
        val response = MockHttpServletResponse()
        val executions = AtomicInteger()

        newFilter().doFilter(
            post(CREDIT_PATH, body = CREDIT_BODY, key = RECIPIENT_CREDIT_KEY),
            response,
            countingChain(executions),
        )

        response.status shouldBe HttpStatus.OK.value()
        response.contentAsString shouldContain "\"applied\""
        executions.get() shouldBe 1
    }

    // 9. The property the money depends on: a retry replays the recorded response instead of
    //    crediting a second time.
    "the same key and the same credit replays instead of executing twice" {
        val filter = newFilter()
        val executions = AtomicInteger()

        val first = MockHttpServletResponse()
        filter.doFilter(post(CREDIT_PATH, body = CREDIT_BODY, key = RECIPIENT_CREDIT_KEY), first, countingChain(executions))

        val retry = MockHttpServletResponse()
        filter.doFilter(post(CREDIT_PATH, body = CREDIT_BODY, key = RECIPIENT_CREDIT_KEY), retry, countingChain(executions))

        executions.get() shouldBe 1
        retry.status shouldBe first.status
        retry.contentAsString shouldBe first.contentAsString
        retry.getHeader(IdempotencyFilter.REPLAY_HEADER) shouldBe "true"
    }

    // The reserve leg goes through the same filter on a different route, so it must claim its own
    // slot rather than replaying the credit's recorded answer.
    "reserve and credit keys do not replay each other" {
        val filter = newFilter()
        val executions = AtomicInteger()

        filter.doFilter(
            post(CREDIT_PATH, body = CREDIT_BODY, key = RECIPIENT_CREDIT_KEY),
            MockHttpServletResponse(),
            countingChain(executions),
        )
        val reserve = MockHttpServletResponse()
        filter.doFilter(
            post(RESERVE_PATH, body = RESERVE_BODY, key = RESERVE_KEY),
            reserve,
            countingChain(executions),
        )

        executions.get() shouldBe 2
        reserve.getHeader(IdempotencyFilter.REPLAY_HEADER) shouldBe null
    }

    // Reusing one key across two routes is the failure the operation suffix prevents: the store
    // is keyed by the key alone, so without distinct suffixes this is what the saga would hit.
    "one key reused across two routes is rejected, not replayed" {
        val filter = newFilter()
        val executions = AtomicInteger()

        filter.doFilter(
            post(CREDIT_PATH, body = CREDIT_BODY, key = SHARED_KEY),
            MockHttpServletResponse(),
            countingChain(executions),
        )
        val clash = MockHttpServletResponse()
        filter.doFilter(post(RESERVE_PATH, body = RESERVE_BODY, key = SHARED_KEY), clash, countingChain(executions))

        clash.status shouldBe HttpStatus.UNPROCESSABLE_ENTITY.value()
        clash.contentAsString shouldContain "idempotency-key-reuse"
        executions.get() shouldBe 1
    }
})

private val ACCOUNT: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val HOLD: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
private val SAGA: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

private val CREDIT_PATH = "/api/v1/accounts/$ACCOUNT/credits"
private val RESERVE_PATH = "/api/v1/accounts/$ACCOUNT/reserves"

private val CREDIT_BODY = """{"amount":"LKR 100.00","reference":"$SAGA"}"""
private val RESERVE_BODY = """{"amount":"LKR 100.00","holdId":"$HOLD"}"""

/** Exactly what `AccountHttpClient` derives, so this test breaks if the derivation drifts. */
private val RECIPIENT_CREDIT_KEY = "$SAGA:credit"
private val RESERVE_KEY = "$HOLD:reserve"
private val SHARED_KEY = SAGA.toString()

private fun newFilter() = IdempotencyFilter(
    store = InMemoryIdempotencyStore(),
    mapper = ObjectMapper(),
    properties = IdempotencyProperties(),
)

private fun post(path: String, body: String, key: String? = null): MockHttpServletRequest =
    MockHttpServletRequest("POST", path).apply {
        contentType = "application/json"
        setContent(body.toByteArray())
        key?.let { addHeader(IdempotencyFilter.HEADER, it) }
    }

/** Stands in for the controller: records that it ran and writes a recognisable success body. */
private fun countingChain(executions: AtomicInteger) = FilterChain { _, response ->
    executions.incrementAndGet()
    val http = response as HttpServletResponse
    http.status = HttpStatus.OK.value()
    http.contentType = "application/json"
    http.writer.write("""{"applied":true}""")
    http.writer.flush()
}
