package org.finix.kernel.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.finix.kernel.idempotency.IdempotencyStore
import org.finix.kernel.idempotency.RedisIdempotencyStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Isolated from [KernelAutoConfiguration] so services without Redis on the classpath
 * (e.g. enclave-runtime) never fail class introspection on [StringRedisTemplate].
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate::class)
class RedisIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate::class)
    @ConditionalOnMissingBean(IdempotencyStore::class)
    fun redisIdempotencyStore(redis: StringRedisTemplate, mapper: ObjectMapper): IdempotencyStore =
        RedisIdempotencyStore(redis, mapper)
}
