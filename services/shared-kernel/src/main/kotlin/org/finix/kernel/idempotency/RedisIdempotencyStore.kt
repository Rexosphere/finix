package org.finix.kernel.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The multi-instance idempotency store.
 *
 * Atomicity comes from `SET key value NX EX ttl`, which Redis executes as one command: exactly
 * one of N concurrent retries gets the key, every other one is told the request is already in
 * flight. This is the property that makes "the customer double-tapped Send on a flaky connection"
 * cost one transfer instead of two, even with the API running behind a load balancer.
 *
 * Records are stored as JSON with the body base64-encoded, so a binary or non-UTF-8 response
 * survives the round trip intact.
 */
class RedisIdempotencyStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) : IdempotencyStore {

    private data class StoredEntry(
        val fingerprint: String,
        val status: Int? = null,
        val contentType: String? = null,
        val bodyBase64: String? = null,
        val recordedAt: String? = null,
    )

    override fun claim(key: String, fingerprint: String, ttl: Duration): Claim {
        val redisKey = redisKey(key)
        val inFlight = mapper.writeValueAsString(StoredEntry(fingerprint))

        // setIfAbsent == SET NX: the single atomic step the whole guarantee rests on.
        val won = redis.opsForValue().setIfAbsent(redisKey, inFlight, ttl) ?: false
        if (won) return Claim.Proceed

        val existing = redis.opsForValue().get(redisKey)
            // Lost the race and then the key expired between the two calls: treat as fresh.
            ?: return Claim.Proceed

        val entry = runCatching { mapper.readValue<StoredEntry>(existing) }.getOrNull()
            ?: return Claim.Proceed

        return when {
            entry.fingerprint != fingerprint -> Claim.FingerprintMismatch
            entry.bodyBase64 == null -> Claim.InFlight
            else -> Claim.Replay(
                RecordedResponse(
                    status = entry.status ?: DEFAULT_REPLAY_STATUS,
                    contentType = entry.contentType,
                    body = Base64.getDecoder().decode(entry.bodyBase64),
                    recordedAt = entry.recordedAt?.let(Instant::parse) ?: Instant.EPOCH,
                ),
            )
        }
    }

    override fun complete(key: String, fingerprint: String, response: RecordedResponse, ttl: Duration) {
        val entry = StoredEntry(
            fingerprint = fingerprint,
            status = response.status,
            contentType = response.contentType,
            bodyBase64 = Base64.getEncoder().encodeToString(response.body),
            recordedAt = response.recordedAt.toString(),
        )
        redis.opsForValue().set(redisKey(key), mapper.writeValueAsString(entry), ttl)
    }

    override fun release(key: String) {
        redis.delete(redisKey(key))
    }

    private fun redisKey(key: String) = "$KEY_PREFIX$key"

    private companion object {
        const val KEY_PREFIX = "finix:idem:"
        const val DEFAULT_REPLAY_STATUS = 200
    }
}
