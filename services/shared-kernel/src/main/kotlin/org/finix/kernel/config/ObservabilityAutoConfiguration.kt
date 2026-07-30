package org.finix.kernel.config

import io.micrometer.core.aop.TimedAspect
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * The observability contract every FINIX service satisfies without configuring anything.
 *
 * Two decisions here are what make the dashboards usable rather than merely present:
 *
 *  1. **Percentile histograms on HTTP timings.** The blueprint commits to p95 < 200 ms (§6.3).
 *     Micrometer's client-side percentiles cannot be aggregated across instances — the mean of
 *     two instances' p95s is not the fleet p95 — so the raw histogram buckets are exported and
 *     Prometheus computes the quantile. Without this the SLO can be estimated but not measured.
 *  2. **Bounded cardinality.** Spring templates URI tags already, but one unbounded tag value
 *     will take a Prometheus down. A runaway tag must degrade a single panel, not the platform.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry::class)
class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun finixMeterFilters(environment: Environment, build: BuildProperties?): FinixMeterPolicy =
        FinixMeterPolicy(
            serviceName = environment.getProperty("spring.application.name", "unknown-service"),
            version = build?.version ?: "dev",
            environmentName = environment.getProperty("finix.environment", "local"),
        )

    /**
     * Enables `@Timed` on use cases, so a hot path becomes instrumented by adding one annotation.
     * Guarded on AspectJ because `enclave-runtime` deliberately runs without the AOP starter.
     */
    @Bean
    @ConditionalOnClass(name = ["org.aspectj.lang.ProceedingJoinPoint"])
    @ConditionalOnMissingBean
    fun timedAspect(registry: MeterRegistry): TimedAspect = TimedAspect(registry)
}

/**
 * Applies FINIX's common tags and its histogram/cardinality policy to whichever registry the
 * service ends up with.
 *
 * Expressed in code rather than YAML because the SLO buckets are part of the service contract:
 * a service must not be able to quietly widen them and still claim to meet the p95 target.
 */
class FinixMeterPolicy(
    private val serviceName: String,
    private val version: String,
    private val environmentName: String,
) : org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer<MeterRegistry> {

    override fun customize(registry: MeterRegistry) {
        registry.config()
            .commonTags("service", serviceName, "version", version, "env", environmentName)
            .meterFilter(HistogramPolicy)
            .meterFilter(MeterFilter.maximumAllowableTags("http.server.requests", "uri", MAX_URI_SERIES, MeterFilter.deny()))
    }

    private object HistogramPolicy : MeterFilter {
        // Micrometer's builder takes varargs; the copy happens once per meter registration, not
        // per observation, so the spread has no measurable cost on the hot path.
        @Suppress("SpreadOperator")
        override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
            if (id.name.startsWith("http.server.requests") || id.name.startsWith("finix.")) {
                DistributionStatisticConfig.builder()
                    .percentilesHistogram(true)
                    .serviceLevelObjectives(*SLO_BUCKET_NANOS)
                    .build()
                    .merge(config)
            } else {
                config
            }
    }

    private companion object {
        /** Straddles the 200 ms p95 target so the SLO burn rate is directly queryable in PromQL. */
        val SLO_BUCKETS: List<Duration> = listOf(
            Duration.ofMillis(10),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
        )
        val SLO_BUCKET_NANOS: DoubleArray = SLO_BUCKETS.map { it.toNanos().toDouble() }.toDoubleArray()
        const val MAX_URI_SERIES = 200
    }
}
