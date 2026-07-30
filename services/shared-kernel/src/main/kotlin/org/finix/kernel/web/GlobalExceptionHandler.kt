package org.finix.kernel.web

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * The single translation point from thrown failures to **RFC 9457 `application/problem+json`**.
 *
 * Every FINIX service returns errors in this one shape, which is what makes a generated client
 * SDK able to handle failures uniformly instead of special-casing each service. Controllers
 * therefore never build error responses themselves, and never catch [DomainException].
 *
 * Two rules are enforced here rather than left to discipline:
 *
 *  1. **No internal detail escapes.** An unexpected exception yields a generic 500 body plus an
 *     `instance` correlation id; the stack trace goes to the log, never to the client. A banking
 *     API that leaks `PSQLException: relation "ledger_entry" ...` has handed an attacker a schema.
 *  2. **Every response is correlatable.** `instance` carries the trace id, so a user-reported
 *     error id resolves directly to a distributed trace.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(DomainException::class)
    fun onDomainException(ex: DomainException, request: HttpServletRequest): ProblemDetail {
        val status = statusFor(ex.error)
        val problem = problem(status, ex.error.code, titleFor(ex.error), ex.error.detail, request)
        ex.error.properties.forEach { (key, value) -> problem.setProperty(key, value) }

        // Integrity violations mean stored state is untrustworthy: alert, do not just count.
        if (ex.error is DomainError.IntegrityViolation) {
            log.error(ex) { "Integrity violation on ${request.method} ${request.requestURI}: ${ex.error.detail}" }
        } else {
            log.debug { "Domain refusal on ${request.method} ${request.requestURI}: ${ex.error}" }
        }
        return problem
    }

    /** Bean-validation failures on a `@RequestBody`, reported per-field so a form can highlight. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onInvalidBody(ex: MethodArgumentNotValidException, request: HttpServletRequest): ProblemDetail {
        val violations = ex.bindingResult.fieldErrors.map {
            mapOf("field" to it.field, "reason" to (it.defaultMessage ?: "is invalid"))
        }
        return problem(
            status = HttpStatus.BAD_REQUEST,
            code = "validation-failed",
            title = "Request validation failed",
            detail = "${violations.size} field(s) did not pass validation",
            request = request,
        ).apply { setProperty("violations", violations) }
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun onInvalidParameter(ex: ConstraintViolationException, request: HttpServletRequest): ProblemDetail {
        val violations = ex.constraintViolations.map {
            mapOf("field" to it.propertyPath.toString(), "reason" to it.message)
        }
        return problem(
            status = HttpStatus.BAD_REQUEST,
            code = "validation-failed",
            title = "Request validation failed",
            detail = "${violations.size} parameter(s) did not pass validation",
            request = request,
        ).apply { setProperty("violations", violations) }
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun onMissingHeader(ex: MissingRequestHeaderException, request: HttpServletRequest): ProblemDetail =
        problem(
            status = HttpStatus.BAD_REQUEST,
            code = "missing-header",
            title = "Required header missing",
            detail = "Header '${ex.headerName}' is required",
            request = request,
        ).apply { setProperty("header", ex.headerName) }

    /** Malformed JSON. The parser message is not echoed back: it quotes the offending payload. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadableBody(ex: HttpMessageNotReadableException, request: HttpServletRequest): ProblemDetail {
        log.debug(ex) { "Unreadable request body on ${request.method} ${request.requestURI}" }
        return problem(
            status = HttpStatus.BAD_REQUEST,
            code = "malformed-body",
            title = "Malformed request body",
            detail = "The request body could not be parsed as valid JSON matching the expected schema",
            request = request,
        )
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun onNoResource(ex: NoResourceFoundException, request: HttpServletRequest): ProblemDetail =
        problem(
            status = HttpStatus.NOT_FOUND,
            code = "not-found",
            title = "Resource not found",
            detail = "No handler for ${ex.httpMethod} ${ex.resourcePath}",
            request = request,
        )

    /**
     * The backstop. Anything reaching here is a defect, so it is logged at ERROR with the full
     * trace and answered with a body that says nothing about the internals.
     */
    @ExceptionHandler(Exception::class)
    fun onUnexpected(ex: Exception, request: HttpServletRequest): ProblemDetail {
        val problem = problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = "internal-error",
            title = "Internal server error",
            detail = "The request could not be completed. Quote the instance id when reporting this.",
            request = request,
        )
        log.error(ex) { "Unhandled ${ex.javaClass.simpleName} on ${request.method} ${request.requestURI} (${problem.instance})" }
        return problem
    }

    private fun problem(
        status: HttpStatus,
        code: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail).apply {
        this.type = URI.create("$PROBLEM_TYPE_BASE/$code")
        this.title = title
        // `instance` is the correlation handle, not the request path: the path is already in
        // the access log, whereas the trace id is what actually finds the failure.
        this.instance = URI.create("urn:finix:trace:${CorrelationContext.traceId()}")
        setProperty("code", code)
        setProperty("timestamp", Instant.now().toString())
        setProperty("path", request.requestURI)
    }

    private fun statusFor(error: DomainError): HttpStatus = when (error) {
        is DomainError.NotFound -> HttpStatus.NOT_FOUND
        is DomainError.Invalid -> HttpStatus.BAD_REQUEST
        is DomainError.Conflict -> HttpStatus.CONFLICT
        is DomainError.ConcurrentModification -> HttpStatus.CONFLICT
        is DomainError.InsufficientFunds -> HttpStatus.UNPROCESSABLE_ENTITY
        is DomainError.Forbidden -> HttpStatus.FORBIDDEN
        is DomainError.LimitExceeded -> HttpStatus.TOO_MANY_REQUESTS
        is DomainError.Unavailable -> HttpStatus.SERVICE_UNAVAILABLE
        is DomainError.IntegrityViolation -> HttpStatus.INTERNAL_SERVER_ERROR
    }

    private fun titleFor(error: DomainError): String = when (error) {
        is DomainError.NotFound -> "Resource not found"
        is DomainError.Invalid -> "Invalid request"
        is DomainError.Conflict -> "Conflicting state"
        is DomainError.ConcurrentModification -> "Concurrent modification"
        is DomainError.InsufficientFunds -> "Insufficient funds"
        is DomainError.Forbidden -> "Forbidden"
        is DomainError.LimitExceeded -> "Limit exceeded"
        is DomainError.Unavailable -> "Dependency unavailable"
        is DomainError.IntegrityViolation -> "Integrity violation"
    }

    companion object {
        /**
         * Problem `type` URIs resolve to human documentation. They are identifiers first — the
         * host never changes even if the docs move, because clients branch on them.
         */
        const val PROBLEM_TYPE_BASE: String = "https://finix.lk/problems"
    }
}
