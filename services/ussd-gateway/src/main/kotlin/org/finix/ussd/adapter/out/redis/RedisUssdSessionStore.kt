package org.finix.ussd.adapter.out.redis

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.finix.ussd.application.port.UssdSessionStore
import org.finix.ussd.config.UssdProperties
import org.finix.ussd.domain.UssdSession
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Redis-backed USSD session state.
 *
 * A USSD dialogue is a sequence of independent HTTP callbacks from the aggregator, so the menu
 * position has to live outside the request. Redis rather than an in-memory map because the
 * gateway is horizontally scaled and consecutive `*334#` inputs from one caller can land on
 * different replicas.
 *
 * Entries expire after [UssdProperties.sessionTtlSeconds]: the aggregator abandons a dialogue
 * silently, so without a TTL every dropped call would leak a key forever.
 */
@Component
class RedisUssdSessionStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    properties: UssdProperties,
) : UssdSessionStore {

    private val ttl: Duration = Duration.ofSeconds(properties.sessionTtlSeconds)

    override fun load(sessionId: String): UssdSession? {
        val json = redis.opsForValue().get(key(sessionId)) ?: return null
        return try {
            mapper.readValue(json, UssdSession::class.java)
        } catch (ex: com.fasterxml.jackson.core.JacksonException) {
            // A session written by an older menu shape is not worth failing the call over —
            // the caller simply starts at the root menu again.
            log.warn { "discarding unreadable USSD session $sessionId: ${ex.message}" }
            redis.delete(key(sessionId))
            null
        }
    }

    override fun save(session: UssdSession) {
        redis.opsForValue().set(key(session.sessionId), mapper.writeValueAsString(session), ttl)
    }

    override fun clear(sessionId: String) {
        redis.delete(key(sessionId))
    }

    private fun key(sessionId: String) = "$KEY_PREFIX$sessionId"

    private companion object {
        const val KEY_PREFIX = "finix:ussd:session:"
    }
}
