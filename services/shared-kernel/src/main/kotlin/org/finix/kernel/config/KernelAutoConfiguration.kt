package org.finix.kernel.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.finix.kernel.idempotency.IdempotencyFilter
import org.finix.kernel.idempotency.IdempotencyProperties
import org.finix.kernel.idempotency.IdempotencyStore
import org.finix.kernel.idempotency.InMemoryIdempotencyStore
import org.finix.kernel.web.CorrelationFilter
import org.finix.kernel.web.GlobalExceptionHandler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

/**
 * Everything a FINIX service gets for free by depending on `shared-kernel`.
 *
 * Redis-backed idempotency lives in [RedisIdempotencyAutoConfiguration] so services without
 * Redis on the classpath still load this class cleanly.
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
            order = Ordered.LOWEST_PRECEDENCE - FILTER_ORDER_OFFSET
        }

    private companion object {
        const val FILTER_ORDER_OFFSET = 100
    }
}
