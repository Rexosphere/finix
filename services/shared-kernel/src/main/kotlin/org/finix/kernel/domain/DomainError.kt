package org.finix.kernel.domain

/**
 * The failure vocabulary of the FINIX core.
 *
 * Every way a use case can legitimately refuse is one of these cases — never a bare
 * `IllegalStateException` and never a raw HTTP status invented at the controller. The mapping to
 * a protocol lives in exactly one place ([org.finix.kernel.web.GlobalExceptionHandler]), so the
 * domain stays free of `jakarta.servlet` and a new channel (gRPC, USSD, Kafka reply) can render
 * the same error without re-deriving what it means.
 *
 * [code] is the machine-readable contract: it appears in the RFC 9457 `type` URI, in support
 * tickets and in client-side branching, so codes are treated as API surface and never renamed.
 * [detail] is human prose and may change freely.
 *
 * Errors carry [properties] rather than string-interpolating values into [detail], because a
 * client that wants to say "you are LKR 300.00 short" should not have to parse English.
 */
sealed class DomainError(
    val code: String,
    val detail: String,
    val properties: Map<String, Any> = emptyMap(),
) {

    /** A referenced aggregate does not exist, or the caller may not know that it does. */
    class NotFound(resource: String, identifier: String) : DomainError(
        code = "not-found",
        detail = "$resource '$identifier' does not exist",
        properties = mapOf("resource" to resource, "identifier" to identifier),
    )

    /** The request is well-formed but violates a domain rule that inputs alone cannot express. */
    class Invalid(detail: String, properties: Map<String, Any> = emptyMap()) :
        DomainError("invalid-request", detail, properties)

    /** The aggregate is real but is in a state where this operation is meaningless. */
    class Conflict(detail: String, properties: Map<String, Any> = emptyMap()) :
        DomainError("state-conflict", detail, properties)

    /**
     * Two writers raced on the same aggregate. Distinct from [Conflict] because it is *retryable*:
     * the caller may simply have lost an optimistic-locking race.
     */
    class ConcurrentModification(resource: String, identifier: String) : DomainError(
        code = "concurrent-modification",
        detail = "$resource '$identifier' was modified concurrently; retry the request",
        properties = mapOf("resource" to resource, "identifier" to identifier),
    )

    /** Money-specific, and deliberately its own case: it is the most common refusal in banking. */
    class InsufficientFunds(accountId: String, requested: Money, available: Money) : DomainError(
        code = "insufficient-funds",
        detail = "Account $accountId has $available available but $requested was requested",
        properties = mapOf(
            "accountId" to accountId,
            "requested" to requested.toString(),
            "available" to available.toString(),
        ),
    )

    /** Authenticated, but not permitted. Authentication failures are the gateway's business. */
    class Forbidden(detail: String, properties: Map<String, Any> = emptyMap()) :
        DomainError("forbidden", detail, properties)

    /**
     * A rate/velocity/exposure limit refused the request. Separated from [Forbidden] because it
     * is time-bound: the same request may succeed later.
     */
    class LimitExceeded(limit: String, detail: String, properties: Map<String, Any> = emptyMap()) :
        DomainError("limit-exceeded", detail, properties + ("limit" to limit))

    /** A downstream port is unreachable or shed the call (circuit open, bulkhead full, timeout). */
    class Unavailable(dependency: String, detail: String) : DomainError(
        code = "dependency-unavailable",
        detail = detail,
        properties = mapOf("dependency" to dependency),
    )

    /**
     * A cryptographic or accounting invariant did not hold: a broken hash link, an unbalanced
     * journal, a shard that fails its VSS commitment.
     *
     * This is the only error that is never the caller's fault and never retryable — it means
     * stored state is untrustworthy, so it is logged at ERROR and alerted on.
     */
    class IntegrityViolation(invariant: String, detail: String, properties: Map<String, Any> = emptyMap()) :
        DomainError("integrity-violation", detail, properties + ("invariant" to invariant))

    override fun toString(): String = "$code: $detail"

    /** Throws this error as the exception the adapter layer catches. */
    fun raise(): Nothing = throw DomainException(this)
}

/**
 * The carrier that lifts a [DomainError] out of a deeply nested call without threading a result
 * type through every intermediate signature.
 *
 * FINIX uses exceptions rather than a `Result<T, DomainError>` monad for domain refusals on
 * purpose: Spring's transaction management keys rollback off thrown exceptions, so a returned
 * failure would commit a half-written transfer unless every call site remembered to roll back
 * by hand. Exactly one handler translates these at the edge, so the "invisible control flow"
 * objection does not apply — nothing in between is expected to catch them.
 *
 * `cause` stays null for domain refusals; it carries the underlying fault only for
 * [DomainError.Unavailable], where the downstream failure is genuine diagnostic information.
 */
class DomainException(
    val error: DomainError,
    cause: Throwable? = null,
) : RuntimeException(error.detail, cause) {

    // Domain refusals are expected control flow at a rate of thousands per second; capturing a
    // stack trace for each is pure overhead, and the handler logs the call site anyway.
    override fun fillInStackTrace(): Throwable = if (error is DomainError.IntegrityViolation) {
        super.fillInStackTrace()
    } else {
        this
    }
}

/** Reads as a guard clause: `require(balance.isPositive) { ... }` for domain rules. */
inline fun domainRequire(condition: Boolean, error: () -> DomainError) {
    if (!condition) throw DomainException(error())
}
