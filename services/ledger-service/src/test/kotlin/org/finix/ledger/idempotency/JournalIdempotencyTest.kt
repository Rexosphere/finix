package org.finix.ledger.idempotency

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
 * The ledger-service half of the contract the orchestrator now satisfies.
 *
 * ledger-service sets no `finix.idempotency` property at all, so this also pins the consequence
 * of that omission: the filter registers with `matchIfMissing = true`, meaning the journal API is
 * enforced by default. A future `enabled: false` added to `application.yml` would break these
 * tests, which is the intent — silently disabling it is exactly what must not happen.
 *
 * The real [IdempotencyFilter] runs over the real journal route; a stub chain counts executions.
 * Counting is the point: the ledger is append-only and hash-chained, so a journal posted twice
 * cannot be deleted, only compensated for.
 */
class JournalIdempotencyTest : StringSpec({

    // 6. The header the orchestrator now sends is accepted; the keyless post it used to send
    //    is not. Both halves asserted, so the fix cannot be mistaken for the filter softening.
    "a keyless journal post is refused with missing-idempotency-key" {
        val response = MockHttpServletResponse()
        val executions = AtomicInteger()

        newFilter().doFilter(post(JOURNALS, FORWARD_BODY), response, countingChain(executions))

        response.status shouldBe HttpStatus.BAD_REQUEST.value()
        response.contentAsString shouldContain "missing-idempotency-key"
        executions.get() shouldBe 0
    }

    "the derived Idempotency-Key is accepted and reaches the handler" {
        val response = MockHttpServletResponse()
        val executions = AtomicInteger()

        newFilter().doFilter(
            post(JOURNALS, FORWARD_BODY, key = FORWARD_KEY),
            response,
            countingChain(executions),
        )

        response.status shouldBe HttpStatus.OK.value()
        executions.get() shouldBe 1
    }

    // 7. The property the ledger depends on: a retry replays rather than posting a second journal.
    "the same key and the same journal cannot post twice" {
        val filter = newFilter()
        val executions = AtomicInteger()

        val first = MockHttpServletResponse()
        filter.doFilter(post(JOURNALS, FORWARD_BODY, key = FORWARD_KEY), first, countingChain(executions))

        val retry = MockHttpServletResponse()
        filter.doFilter(post(JOURNALS, FORWARD_BODY, key = FORWARD_KEY), retry, countingChain(executions))

        executions.get() shouldBe 1
        retry.status shouldBe first.status
        retry.contentAsString shouldBe first.contentAsString
        retry.getHeader(IdempotencyFilter.REPLAY_HEADER) shouldBe "true"
    }

    // Compensation must be able to post its reversal after the forward leg already posted.
    // If the two shared a key this would replay the forward response and never reverse anything.
    "a reversal posts even though the forward journal already claimed its key" {
        val filter = newFilter()
        val executions = AtomicInteger()

        filter.doFilter(
            post(JOURNALS, FORWARD_BODY, key = FORWARD_KEY),
            MockHttpServletResponse(),
            countingChain(executions),
        )
        val reversal = MockHttpServletResponse()
        filter.doFilter(post(JOURNALS, REVERSAL_BODY, key = REVERSAL_KEY), reversal, countingChain(executions))

        executions.get() shouldBe 2
        reversal.getHeader(IdempotencyFilter.REPLAY_HEADER) shouldBe null
    }

    // What the distinct transaction ids protect against: one key covering both bodies is a 422,
    // not a replay, because the filter stores by key alone and fingerprints the body.
    "one key reused for two different journals is rejected, not replayed" {
        val filter = newFilter()
        val executions = AtomicInteger()

        filter.doFilter(
            post(JOURNALS, FORWARD_BODY, key = SHARED_KEY),
            MockHttpServletResponse(),
            countingChain(executions),
        )
        val clash = MockHttpServletResponse()
        filter.doFilter(post(JOURNALS, REVERSAL_BODY, key = SHARED_KEY), clash, countingChain(executions))

        clash.status shouldBe HttpStatus.UNPROCESSABLE_ENTITY.value()
        clash.contentAsString shouldContain "idempotency-key-reuse"
        executions.get() shouldBe 1
    }
})

private const val JOURNALS = "/api/v1/ledger/journals"

private val SAGA: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

/** `RunTransferSagaUseCase.reversalTransactionId` is on the orchestrator's classpath, not here. */
private val REVERSAL: UUID = UUID.nameUUIDFromBytes("reversal:$SAGA".toByteArray())

private val FROM: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val TO: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

private val FORWARD_BODY = journalBody(SAGA, debit = FROM, credit = TO)
private val REVERSAL_BODY = journalBody(REVERSAL, debit = TO, credit = FROM)

/** Exactly what `LedgerHttpClient` derives, so this test breaks if the derivation drifts. */
private val FORWARD_KEY = "$SAGA:journal"
private val REVERSAL_KEY = "$REVERSAL:journal"
private val SHARED_KEY = SAGA.toString()

private fun journalBody(transactionId: UUID, debit: UUID, credit: UUID): String =
    """
    {"transactionId":"$transactionId","lines":[
      {"accountId":"$debit","side":"DEBIT","amount":"LKR 100.00"},
      {"accountId":"$credit","side":"CREDIT","amount":"LKR 100.00"}]}
    """.trimIndent()

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
    http.writer.write("""{"posted":true}""")
    http.writer.flush()
}
