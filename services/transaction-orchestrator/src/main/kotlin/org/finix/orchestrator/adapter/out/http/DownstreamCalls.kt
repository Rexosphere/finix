package org.finix.orchestrator.adapter.out.http

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/** Shared call timeout for downstream services; the connector already caps connect/response time. */
internal val DOWNSTREAM_TIMEOUT: Duration = Duration.ofSeconds(10)

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
        if (ex.statusCode.is4xxClientError) {
            throw DomainException(
                DomainError.Conflict(
                    detail = "$dependency refused the request with HTTP ${ex.statusCode.value()}: ${ex.responseBodyAsString}",
                    properties = mapOf("dependency" to dependency, "status" to ex.statusCode.value()),
                ),
                ex,
            )
        }
        throw DomainException(
            DomainError.Unavailable(dependency, "$dependency returned HTTP ${ex.statusCode.value()}"),
            ex,
        )
    } catch (ex: WebClientRequestException) {
        throw DomainException(DomainError.Unavailable(dependency, "$dependency is unreachable: ${ex.message}"), ex)
    }
