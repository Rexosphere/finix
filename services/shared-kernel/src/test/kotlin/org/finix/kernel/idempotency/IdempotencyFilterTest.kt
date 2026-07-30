package org.finix.kernel.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * These tests are written against the failure modes that actually cost money: a customer
 * double-tapping Send on a flaky connection, the offline queue replaying on reconnect, and
 * Resilience4j retrying a request that already succeeded.
 */
class IdempotencyFilterTest : StringSpec({

    val mapper = ObjectMapper().registerKotlinModule()
    val properties = IdempotencyProperties(ttl = Duration.ofMinutes(5))

    fun filter(store: IdempotencyStore) = IdempotencyFilter(store, mapper, properties)

    fun transferRequest(key: String?, body: String = """{"to":"acc-2","amount":"LKR 500.00"}"""): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/transfers").apply {
            contentType = MediaType.APPLICATION_JSON_VALUE
            setContent(body.toByteArray())
            key?.let { addHeader(IdempotencyFilter.HEADER, it) }
        }

    /** A handler that records how many times it actually ran. */
    fun countingChain(counter: AtomicInteger, payload: String = """{"transactionId":"tx-1"}"""): FilterChain =
        FilterChain { _: ServletRequest, response: ServletResponse ->
            counter.incrementAndGet()
            (response as HttpServletResponse).status = HttpStatus.CREATED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.outputStream.write(payload.toByteArray())
        }

    "a retried transfer executes once and replays the original response" {
        val store = InMemoryIdempotencyStore()
        val executions = AtomicInteger()
        val first = MockHttpServletResponse()
        filter(store).doFilter(transferRequest("key-1"), first, countingChain(executions))

        val second = MockHttpServletResponse()
        filter(store).doFilter(transferRequest("key-1"), second, countingChain(executions))

        executions.get() shouldBe 1
        second.status shouldBe HttpStatus.CREATED.value()
        second.contentAsString shouldBe first.contentAsString
        second.getHeader(IdempotencyFilter.REPLAY_HEADER) shouldBe "true"
        first.getHeader(IdempotencyFilter.REPLAY_HEADER) shouldBe null
    }

    "a missing Idempotency-Key is rejected before the handler runs" {
        val executions = AtomicInteger()
        val response = MockHttpServletResponse()
        filter(InMemoryIdempotencyStore()).doFilter(transferRequest(key = null), response, countingChain(executions))

        executions.get() shouldBe 0
        response.status shouldBe HttpStatus.BAD_REQUEST.value()
        response.contentType shouldContain MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.contentAsString shouldContain "missing-idempotency-key"
    }

    "reusing a key with a different body is a 422, not a silent replay" {
        // The dangerous case: "pay Nimal 500" must never answer a request that said
        // "pay Kamal 50000" just because the client reused a key.
        val store = InMemoryIdempotencyStore()
        val executions = AtomicInteger()
        filter(store).doFilter(transferRequest("key-2"), MockHttpServletResponse(), countingChain(executions))

        val response = MockHttpServletResponse()
        val different = transferRequest("key-2", body = """{"to":"acc-9","amount":"LKR 50000.00"}""")
        filter(store).doFilter(different, response, countingChain(executions))

        executions.get() shouldBe 1
        response.status shouldBe HttpStatus.UNPROCESSABLE_ENTITY.value()
        response.contentAsString shouldContain "idempotency-key-reuse"
    }

    "the same key on a different endpoint is treated as reuse" {
        val store = InMemoryIdempotencyStore()
        val executions = AtomicInteger()
        filter(store).doFilter(transferRequest("key-3"), MockHttpServletResponse(), countingChain(executions))

        val loanRequest = MockHttpServletRequest("POST", "/loans").apply {
            setContent("""{"to":"acc-2","amount":"LKR 500.00"}""".toByteArray())
            addHeader(IdempotencyFilter.HEADER, "key-3")
        }
        val response = MockHttpServletResponse()
        filter(store).doFilter(loanRequest, response, countingChain(executions))

        response.status shouldBe HttpStatus.UNPROCESSABLE_ENTITY.value()
        executions.get() shouldBe 1
    }

    "a server error releases the key so the request stays retryable" {
        val store = InMemoryIdempotencyStore()
        val executions = AtomicInteger()
        val failing = FilterChain { _: ServletRequest, response: ServletResponse ->
            executions.incrementAndGet()
            (response as HttpServletResponse).status = HttpStatus.SERVICE_UNAVAILABLE.value()
        }
        filter(store).doFilter(transferRequest("key-4"), MockHttpServletResponse(), failing)

        // The retry must reach the handler again rather than replaying a frozen 503.
        val retry = MockHttpServletResponse()
        filter(store).doFilter(transferRequest("key-4"), retry, countingChain(executions))

        executions.get() shouldBe 2
        retry.status shouldBe HttpStatus.CREATED.value()
    }

    "a rejected request stays rejected, so a limit check cannot be brute-forced by resending" {
        val store = InMemoryIdempotencyStore()
        val executions = AtomicInteger()
        val rejecting = FilterChain { _: ServletRequest, response: ServletResponse ->
            executions.incrementAndGet()
            (response as HttpServletResponse).status = HttpStatus.UNPROCESSABLE_ENTITY.value()
            response.outputStream.write("""{"code":"insufficient-funds"}""".toByteArray())
        }
        filter(store).doFilter(transferRequest("key-5"), MockHttpServletResponse(), rejecting)

        val retry = MockHttpServletResponse()
        filter(store).doFilter(transferRequest("key-5"), retry, rejecting)

        executions.get() shouldBe 1
        retry.contentAsString shouldContain "insufficient-funds"
    }

    "a thrown handler exception releases the key and does not record a response" {
        val store = InMemoryIdempotencyStore()
        val exploding = FilterChain { _, _ -> throw IllegalStateException("database down") }
        runCatching {
            filter(store).doFilter(transferRequest("key-6"), MockHttpServletResponse(), exploding)
        }

        val executions = AtomicInteger()
        val retry = MockHttpServletResponse()
        filter(store).doFilter(transferRequest("key-6"), retry, countingChain(executions))
        executions.get() shouldBe 1
        retry.status shouldBe HttpStatus.CREATED.value()
    }

    "safe methods and exempt paths pass through without the header" {
        // GET is idempotent by definition; actuator probes and the USSD endpoint cannot set the
        // header at all, because the telco owns that request shape.
        val exempt = listOf(
            MockHttpServletRequest("GET", "/accounts/acc-1"),
            MockHttpServletRequest("POST", "/actuator/refresh"),
            MockHttpServletRequest("POST", "/ussd"),
        )
        exempt.forEach { request ->
            val executions = AtomicInteger()
            val response = MockHttpServletResponse()
            filter(InMemoryIdempotencyStore()).doFilter(request, response, countingChain(executions))
            executions.get() shouldBe 1
            response.status shouldBe HttpStatus.CREATED.value()
        }
    }

    "an oversized key is rejected rather than stored" {
        val response = MockHttpServletResponse()
        val executions = AtomicInteger()
        filter(InMemoryIdempotencyStore())
            .doFilter(transferRequest("k".repeat(300)), response, countingChain(executions))
        response.status shouldBe HttpStatus.BAD_REQUEST.value()
        executions.get() shouldBe 0
    }

    "the handler still sees the request body after it has been fingerprinted" {
        val store = InMemoryIdempotencyStore()
        var seen: String? = null
        val echoing = FilterChain { request: ServletRequest, response: ServletResponse ->
            seen = request.inputStream.readBytes().decodeToString()
            (response as HttpServletResponse).status = HttpStatus.OK.value()
        }
        filter(store).doFilter(transferRequest("key-7"), MockHttpServletResponse(), echoing)
        seen shouldBe """{"to":"acc-2","amount":"LKR 500.00"}"""
    }
})

/**
 * The property the whole filter rests on: claiming a key is atomic. If two concurrent retries
 * could both see `Proceed`, everything above it is decoration.
 */
class IdempotencyStoreConcurrencyTest : StringSpec({

    "only one of many concurrent claims on the same key proceeds" {
        val store = InMemoryIdempotencyStore()
        val threads = 32
        val start = CountDownLatch(1)
        val proceeded = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.submit {
                    start.await()
                    if (store.claim("race", "fingerprint", Duration.ofMinutes(1)) is Claim.Proceed) {
                        proceeded.incrementAndGet()
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
        } finally {
            pool.shutdownNow()
        }
        proceeded.get() shouldBe 1
    }

    "an expired claim is reclaimable, so a crashed request does not block its key forever" {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val store = InMemoryIdempotencyStore { now }
        store.claim("key", "fp", Duration.ofMinutes(5)) shouldBe Claim.Proceed
        store.claim("key", "fp", Duration.ofMinutes(5)) shouldBe Claim.InFlight

        now = now.plusSeconds(600)
        store.claim("key", "fp", Duration.ofMinutes(5)) shouldBe Claim.Proceed
    }

    "completed responses replay until they expire, then evict" {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val store = InMemoryIdempotencyStore { now }
        val recorded = RecordedResponse(201, "application/json", """{"id":1}""".toByteArray(), now)
        store.claim("key", "fp", Duration.ofMinutes(5))
        store.complete("key", "fp", recorded, Duration.ofMinutes(5))

        store.claim("key", "fp", Duration.ofMinutes(5)) shouldBe Claim.Replay(recorded)

        now = now.plusSeconds(600)
        store.evictExpired()
        store.claim("key", "fp", Duration.ofMinutes(5)) shouldBe Claim.Proceed
    }

    "release makes a key immediately available again" {
        val store = InMemoryIdempotencyStore()
        store.claim("key", "fp", Duration.ofMinutes(5)) shouldBe Claim.Proceed
        store.release("key")
        store.claim("key", "fp", Duration.ofMinutes(5)) shouldBe Claim.Proceed
    }
})
