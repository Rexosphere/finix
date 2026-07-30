package org.finix.kernel.idempotency

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A replayable record of what a mutating request returned.
 *
 * The status and body are stored, not just "this key was seen", because the whole point is that
 * a retry receives the *same answer* — a client that retries a transfer must get back the
 * original transaction id, not a bare 200 with no payload.
 */
data class RecordedResponse(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    val recordedAt: Instant,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is RecordedResponse &&
                status == other.status &&
                contentType == other.contentType &&
                body.contentEquals(other.body) &&
                recordedAt == other.recordedAt
            )

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + body.contentHashCode()
        result = 31 * result + recordedAt.hashCode()
        return result
    }
}

/** The outcome of claiming an idempotency key, which decides what the filter does next. */
sealed interface Claim {
    /** First time this key has been seen — proceed and record the result. */
    data object Proceed : Claim

    /** The original request completed; return [response] verbatim without re-executing. */
    data class Replay(val response: RecordedResponse) : Claim

    /** The original request is still running. The client must retry, not double-submit. */
    data object InFlight : Claim

    /** The key was reused with a different request body — almost always a client bug. */
    data object FingerprintMismatch : Claim
}

/**
 * Port for idempotency-key storage.
 *
 * [claim] must be **atomic**: two concurrent retries of the same transfer arriving on two
 * instances must not both see `Proceed`. Any implementation that reads-then-writes is wrong;
 * the money is on the line here, not just a duplicate log line.
 */
interface IdempotencyStore {

    /** Atomically claims [key] for a request whose body hashes to [fingerprint]. */
    fun claim(key: String, fingerprint: String, ttl: Duration): Claim

    /** Publishes the result so subsequent retries replay it. */
    fun complete(key: String, fingerprint: String, response: RecordedResponse, ttl: Duration)

    /**
     * Releases a claim whose request failed before producing a recordable response.
     *
     * Failures are deliberately *not* recorded: a transfer that fell over with a 503 must be
     * retryable, and pinning the 503 for 24 hours would make the outage permanent for that key.
     */
    fun release(key: String)
}

/**
 * Single-node fallback so a service starts and behaves correctly without Redis — used by tests
 * and by the `core` compose profile.
 *
 * It is honest about its limit: with more than one replica two instances have separate maps, so
 * this guarantees idempotency per instance only. Production runs the Redis store; that is why
 * [RedisIdempotencyStore] takes precedence whenever a `StringRedisTemplate` exists.
 */
class InMemoryIdempotencyStore(
    private val clock: () -> Instant = Instant::now,
) : IdempotencyStore {

    private data class Entry(val fingerprint: String, val response: RecordedResponse?, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<String, Entry>()

    override fun claim(key: String, fingerprint: String, ttl: Duration): Claim {
        val now = clock()
        // `compute` holds the bin lock, which is what makes the check-and-set atomic.
        var outcome: Claim = Claim.Proceed
        entries.compute(key) { _, existing ->
            if (existing == null || existing.expiresAt.isBefore(now)) {
                outcome = Claim.Proceed
                Entry(fingerprint, response = null, expiresAt = now.plus(ttl))
            } else {
                outcome = when {
                    existing.fingerprint != fingerprint -> Claim.FingerprintMismatch
                    existing.response != null -> Claim.Replay(existing.response)
                    else -> Claim.InFlight
                }
                existing
            }
        }
        return outcome
    }

    override fun complete(key: String, fingerprint: String, response: RecordedResponse, ttl: Duration) {
        entries[key] = Entry(fingerprint, response, clock().plus(ttl))
    }

    override fun release(key: String) {
        entries.remove(key)
    }

    /** Reclaims expired keys; the filter is not on a schedule so eviction is opportunistic. */
    fun evictExpired() {
        val now = clock()
        entries.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }
}
