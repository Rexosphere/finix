package org.finix.orchestrator.adapter.out.http

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.idempotency.IdempotencyFilter
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.util.UUID

/** Shared call timeout for downstream services; the connector already caps connect/response time. */
internal val DOWNSTREAM_TIMEOUT: Duration = Duration.ofSeconds(10)

/** Every mutating downstream call needs its own key — account/ledger enforce the header. */
internal fun <S : WebClient.RequestHeadersSpec<*>> S.withIdempotencyKey(): S {
    @Suppress("UNCHECKED_CAST")
    return header(IdempotencyFilter.HEADER, UUID.randomUUID().toString()) as S
}

/**
 * Translates transport failures into the domain's failure vocabulary.
 *
 * The saga treats any exception as a reason to compensate, so the distinction that matters here
 * is diagnostic: a 4xx means the downstream refused this specific request (and the body carries
 * why), while a 5xx or a connection error means the dependency itself is down.
 */
internal fun <T> callDownstream(dependency: String, block: () -> T): T =
    try {
        block()
    } catch (ex: WebClientResponseException) {
        val reason = summarizeDownstreamBody(ex.responseBodyAsString)
        if (ex.statusCode.is4xxClientError) {
            throw DomainException(
                DomainError.Conflict(
                    detail = "$dependency refused the request: $reason",
                    properties = mapOf("dependency" to dependency, "status" to ex.statusCode.value()),
                ),
                ex,
            )
        }
        throw DomainException(
            DomainError.Unavailable(dependency, "$dependency returned HTTP ${ex.statusCode.value()}: $reason"),
            ex,
        )
    } catch (ex: WebClientRequestException) {
        throw DomainException(DomainError.Unavailable(dependency, "$dependency is unreachable: ${ex.message}"), ex)
    }

/** Prefer problem+json `detail` over dumping the whole body into the saga failure reason. */
internal fun summarizeDownstreamBody(body: String?): String {
    if (body.isNullOrBlank()) return "no body"
    val trimmed = body.trim()
    if (!trimmed.startsWith("{")) return trimmed.take(240)
    return try {
        val detail = Regex("\"detail\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(trimmed)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
        val title = Regex("\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(trimmed)
            ?.groupValues
            ?.get(1)
        detail ?: title ?: trimmed.take(240)
    } catch (_: Exception) {
        trimmed.take(240)
    }
}
