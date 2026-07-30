package org.finix.kernel.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.finix.kernel.idempotency.IdempotencyFilter
import org.finix.kernel.idempotency.IdempotencyProperties
import org.finix.kernel.idempotency.IdempotencyStore
import org.finix.kernel.idempotency.InMemoryIdempotencyStore
import org.finix.kernel.idempotency.RedisIdempotencyStore
import org.finix.kernel.web.CorrelationFilter
import org.finix.kernel.web.GlobalExceptionHandler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Everything a FINIX service gets for free by depending on `shared-kernel`.
 *
 * This is an auto-configuration rather than a `@Configuration` each service imports, so that
 * cross-cutting guarantees cannot be forgotten: a new service is correct by construction, and
 * "we forgot to register the idempotency filter in loan-service" is not a possible bug.
 *
 * Every bean is `@ConditionalOnMissingBean`, so a service that genuinely needs different
 * behaviour overrides it by declaring its own — opinionated, not authoritarian.
 */
@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties::class)
class KernelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun globalExceptionHandler(): GlobalExceptionHandler = GlobalExceptionHandler()

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    fun correlationFilter(): CorrelationFilter = CorrelationFilter()

    /**
     * Redis wins whenever it is present: it is the only variant that stays correct across
     * replicas. [InMemoryIdempotencyStore] exists so a single-instance dev run — and every unit
     * test — still exercises the same filter code path rather than a disabled one.
     */
    @Bean
    @ConditionalOnClass(StringRedisTemplate::class)
    @ConditionalOnBean(StringRedisTemplate::class)
    @ConditionalOnMissingBean(IdempotencyStore::class)
    fun redisIdempotencyStore(redis: StringRedisTemplate, mapper: ObjectMapper): IdempotencyStore =
        RedisIdempotencyStore(redis, mapper)

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore::class)
    fun inMemoryIdempotencyStore(): IdempotencyStore = InMemoryIdempotencyStore()

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "finix.idempotency", name = ["enabled"], matchIfMissing = true)
    fun idempotencyFilterRegistration(
        store: IdempotencyStore,
        mapper: ObjectMapper,
        properties: IdempotencyProperties,
    ): FilterRegistrationBean<IdempotencyFilter> =
        FilterRegistrationBean(IdempotencyFilter(store, mapper, properties)).apply {
            // Late in the chain: a caller must be authenticated before their key is trusted,
            // but the filter must still run before any controller work happens.
            order = Ordered.LOWEST_PRECEDENCE - FILTER_ORDER_OFFSET
        }

    private companion object {
        const val FILTER_ORDER_OFFSET = 100
    }
}
