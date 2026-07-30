package org.finix.kernel.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Reads correlation identifiers out of the calling context.
 *
 * Values live in SLF4J's [MDC] rather than a bespoke `ThreadLocal` for one reason: Logback's
 * JSON encoder already serialises the MDC, so a value put here appears in every log line of the
 * request without any logging call being changed. OpenTelemetry's Spring instrumentation writes
 * `traceId`/`spanId` into the same map, so when tracing is enabled the ids in the logs and the
 * ids in Tempo are literally the same values.
 *
 * On virtual threads the MDC is still per-thread and each request gets its own carrier thread,
 * so the usual thread-pool leakage concern does not apply — but [CorrelationFilter] clears it in
 * a `finally` regardless.
 */
object CorrelationContext {

    const val TRACE_ID: String = "traceId"
    const val CORRELATION_ID: String = "correlationId"
    const val USER_ID: String = "userId"

    /** Never null: an uncorrelated log line is worse than a synthetic id. */
    fun traceId(): String = MDC.get(TRACE_ID) ?: "untraced"

    fun correlationId(): String? = MDC.get(CORRELATION_ID)

    fun userId(): String? = MDC.get(USER_ID)

    fun put(key: String, value: String?) {
        if (value.isNullOrBlank()) MDC.remove(key) else MDC.put(key, value)
    }
}

/**
 * Establishes request correlation before anything else runs.
 *
 * `X-Correlation-Id` is *accepted* from the caller so that a business flow spanning several
 * services (a transfer touching orchestrator → account → ledger) can be reassembled end to end,
 * and generated when absent. It is always echoed back, which is what lets a support agent ask a
 * customer for one id and find every log line the request produced.
 *
 * A caller-supplied value is length-capped and sanitised: it lands in log files and response
 * headers, so an unbounded value is a log-injection and header-splitting vector.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CorrelationFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = sanitise(request.getHeader(HEADER)) ?: UUID.randomUUID().toString()
        CorrelationContext.put(CorrelationContext.CORRELATION_ID, correlationId)
        // Tracing instrumentation overwrites this with the real trace id when it is active;
        // without a tracer the correlation id doubles as one so `instance` is never empty.
        if (MDC.get(CorrelationContext.TRACE_ID) == null) {
            CorrelationContext.put(CorrelationContext.TRACE_ID, correlationId)
        }
        response.setHeader(HEADER, correlationId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(CorrelationContext.CORRELATION_ID)
            MDC.remove(CorrelationContext.TRACE_ID)
            MDC.remove(CorrelationContext.USER_ID)
        }
    }

    /** Printable ASCII only, bounded length — this value is written to logs and headers. */
    private fun sanitise(raw: String?): String? = raw
        ?.take(MAX_LENGTH)
        ?.filter { it.code in PRINTABLE_ASCII }
        ?.takeIf { it.isNotEmpty() }

    companion object {
        const val HEADER: String = "X-Correlation-Id"
        private const val MAX_LENGTH = 128

        /** `!` through `~`: excludes control characters (log injection) and space (header splitting). */
        private val PRINTABLE_ASCII = 0x21..0x7E
    }
}
