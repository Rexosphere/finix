package org.finix.kernel.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.finix.kernel.crypto.Hashing
import org.finix.kernel.web.CorrelationContext
import org.finix.kernel.web.GlobalExceptionHandler
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Enforces `Idempotency-Key` on every state-changing request, per the IETF
 * *Idempotency-Key Header Field* draft.
 *
 * Retries are not an edge case in this system. The PWA queues transfers while offline and
 * replays them on reconnect; the USSD gateway retries on GSM timeouts; Resilience4j retries on
 * 5xx. Without this filter each of those paths can debit an account twice.
 *
 * The design decisions worth defending:
 *
 *  - **Enforced, not offered.** A missing key is a 400. An optional header would be honoured by
 *    exactly the clients that already retry safely.
 *  - **The body is fingerprinted.** Reusing a key with a different payload returns 422 rather
 *    than silently replaying the old answer — that combination is a client bug, and masking it
 *    would let "pay Nimal 500" answer a request that said "pay Kamal 50 000".
 *  - **Only successful responses are recorded.** A 5xx releases the key, so a transient failure
 *    stays retryable instead of being frozen for the whole TTL.
 *  - **Concurrent duplicates get 409, not a queue.** Two in-flight copies of the same request
 *    must not both execute, and holding the second open would tie up a connection per retry.
 */
class IdempotencyFilter(
    private val store: IdempotencyStore,
    private val mapper: ObjectMapper,
    private val properties: IdempotencyProperties,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in MUTATING_METHODS ||
            properties.excludedPaths.any { request.requestURI.startsWith(it) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val key = request.getHeader(HEADER)?.trim()
        if (key.isNullOrEmpty()) {
            writeProblem(response, request, HttpStatus.BAD_REQUEST, "missing-idempotency-key", MISSING_DETAIL)
            return
        }
        if (key.length > MAX_KEY_LENGTH) {
            writeProblem(response, request, HttpStatus.BAD_REQUEST, "invalid-idempotency-key", OVERSIZED_DETAIL)
            return
        }

        val buffered = CachedBodyRequest(request)
        val fingerprint = fingerprint(request, buffered.body)

        when (val claim = store.claim(key, fingerprint, properties.ttl)) {
            is Claim.Replay -> replay(response, claim.response)
            Claim.InFlight -> writeProblem(response, request, HttpStatus.CONFLICT, "request-in-flight", IN_FLIGHT_DETAIL)
            Claim.FingerprintMismatch ->
                writeProblem(response, request, HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reuse", REUSE_DETAIL)
            Claim.Proceed -> executeAndRecord(buffered, response, filterChain, key, fingerprint)
        }
    }

    private fun executeAndRecord(
        request: CachedBodyRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
        key: String,
        fingerprint: String,
    ) {
        val caching = ContentCachingResponseWrapper(response)
        var recorded = false
        try {
            filterChain.doFilter(request, caching)
            // 4xx is recorded too: a rejected transfer must stay rejected on retry, otherwise a
            // client can brute-force a limit check by resending the same key.
            if (caching.status < HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                store.complete(
                    key = key,
                    fingerprint = fingerprint,
                    response = RecordedResponse(
                        status = caching.status,
                        contentType = caching.contentType,
                        body = caching.contentAsByteArray,
                        recordedAt = Instant.now(),
                    ),
                    ttl = properties.ttl,
                )
                recorded = true
            }
        } finally {
            if (!recorded) {
                releaseQuietly(key)
            }
            caching.copyBodyToResponse()
        }
    }

    /** A failed release must never mask the original failure that is already unwinding. */
    private fun releaseQuietly(key: String) {
        runCatching { store.release(key) }
            .onFailure { log.warn(it) { "Could not release idempotency key after a failed request" } }
    }

    private fun replay(response: HttpServletResponse, recorded: RecordedResponse) {
        response.status = recorded.status
        recorded.contentType?.let { response.contentType = it }
        response.setHeader(REPLAY_HEADER, "true")
        response.setHeader(REPLAY_AT_HEADER, recorded.recordedAt.toString())
        response.outputStream.write(recorded.body)
        response.outputStream.flush()
    }

    /**
     * Binds the key to the *request*, not just to the caller. Method and path are included so a
     * key generated for `POST /transfers` cannot be replayed against `POST /loans`.
     */
    private fun fingerprint(request: HttpServletRequest, body: ByteArray): String =
        Hashing.sha256Hex(
            request.method.toByteArray(),
            request.requestURI.toByteArray(),
            (request.queryString ?: "").toByteArray(),
            body,
        )

    private fun writeProblem(
        response: HttpServletResponse,
        request: HttpServletRequest,
        status: HttpStatus,
        code: String,
        detail: String,
    ) {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("${GlobalExceptionHandler.PROBLEM_TYPE_BASE}/$code")
            title = code.replace('-', ' ').replaceFirstChar(Char::titlecase)
            instance = URI.create("urn:finix:trace:${CorrelationContext.traceId()}")
            setProperty("code", code)
            setProperty("path", request.requestURI)
            setProperty("header", HEADER)
        }
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        mapper.writeValue(response.outputStream, problem)
    }

    /**
     * Buffers the body so it can be hashed *before* the controller reads it. Servlet input
     * streams are single-pass, so without this the fingerprint and the handler would compete
     * for the same bytes.
     */
    private class CachedBodyRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        val body: ByteArray = request.inputStream.readBytes()

        override fun getInputStream(): ServletInputStream {
            val delegate = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = delegate.read()
                override fun isFinished(): Boolean = delegate.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) = Unit
            }
        }

        override fun getReader() = body.inputStream().bufferedReader(charset(characterEncoding ?: "UTF-8"))
    }

    companion object {
        const val HEADER: String = "Idempotency-Key"
        const val REPLAY_HEADER: String = "Idempotency-Replayed"
        const val REPLAY_AT_HEADER: String = "Idempotency-Recorded-At"

        private const val MAX_KEY_LENGTH = 255
        private val MUTATING_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

        private const val MISSING_DETAIL =
            "State-changing requests must carry an Idempotency-Key header (a client-generated UUID)."
        private const val OVERSIZED_DETAIL = "Idempotency-Key must be at most 255 characters."
        private const val IN_FLIGHT_DETAIL =
            "A request with this Idempotency-Key is still being processed. Retry shortly; do not resubmit."
        private const val REUSE_DETAIL =
            "This Idempotency-Key was already used for a request with a different body."
    }
}
