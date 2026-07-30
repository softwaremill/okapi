package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.micrometer.MicrometerOutboxListener
import com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics
import com.softwaremill.okapi.micrometer.OutboxMetricsRefresher
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Autoconfiguration for Okapi Micrometer observability beans.
 *
 * Separated from [OutboxAutoConfiguration] as a top-level autoconfiguration
 * so that [ConditionalOnBean] for [MeterRegistry] evaluates after the meter
 * registry is created by Spring Boot's metrics autoconfiguration.
 *
 * All beans are `@ConditionalOnMissingBean` — define your own to override defaults.
 *
 * The [OutboxMetricsRefresher] bean periodically calls [MicrometerOutboxMetrics.refresh],
 * managing its own daemon thread (no `@EnableScheduling` required).
 */
@AutoConfiguration
@AutoConfigureAfter(
    name = [
        // Spring Boot 3.5.x
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        // Spring Boot 4.0.x
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
    ],
)
@ConditionalOnClass(
    name = [
        // A consuming app can easily have MeterRegistry on the classpath (e.g. via Spring Boot
        // Actuator) without depending on okapi-micrometer at all -- MeterRegistry alone is not
        // evidence okapi-micrometer is present. This class directly references okapi-micrometer
        // types (MicrometerOutboxListener/MicrometerOutboxMetrics/OutboxMetricsRefresher) below, so
        // without this guard, Spring's condition/annotation evaluation would try to load this class
        // on such a classpath and fail with NoClassDefFoundError instead of just skipping it.
        "io.micrometer.core.instrument.MeterRegistry",
        "com.softwaremill.okapi.micrometer.MicrometerOutboxListener",
        "com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics",
        "com.softwaremill.okapi.micrometer.OutboxMetricsRefresher",
    ],
)
@ConditionalOnBean(MeterRegistry::class)
@EnableConfigurationProperties(OkapiMetricsProperties::class, OkapiProperties::class)
class OkapiMicrometerAutoConfiguration {

    /**
     * Registers okapi's Micrometer beans on @ConditionalOnProperty(`okapi.metrics.enabled=true`).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "okapi.metrics", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    class MetricsConfiguration {
        init {
            logger.info(
                "okapi.metrics.enabled=true. Okapi registers MicrometerOutboxListener, " +
                    "MicrometerOutboxMetrics, OutboxMetricsRefresher. okapi.* counters, timers, " +
                    "will be published",
            )
        }

        @Bean
        @ConditionalOnMissingBean
        fun micrometerOutboxListener(registry: MeterRegistry): MicrometerOutboxListener = MicrometerOutboxListener(registry)

        @Bean
        @ConditionalOnMissingBean
        fun micrometerOutboxMetrics(
            store: OutboxStore,
            registry: MeterRegistry,
            transactionManager: ObjectProvider<PlatformTransactionManager>,
            clock: ObjectProvider<Clock>,
            beanFactory: BeanFactory,
            okapiProperties: OkapiProperties,
        ): MicrometerOutboxMetrics {
            // The PTM is optional here (metrics work fine without a read-only runner), so we can't reuse
            // OutboxAutoConfiguration.resolvePlatformTransactionManager() as-is — it throws when no PTM is
            // found. When a qualifier is set it still must be honoured (explicit user config), via the
            // shared resolvePlatformTransactionManagerByQualifier(); otherwise fall back to
            // getIfUnique(), which returns null instead of throwing when multiple PTMs are present. This
            // fixes issue #80: getIfAvailable() throws NoUniqueBeanDefinitionException with 2+ PTM beans,
            // even when okapi.transaction-manager-qualifier disambiguates which one to use.
            val ptm = okapiProperties.transactionManagerQualifier?.let { qualifier ->
                OutboxAutoConfiguration.resolvePlatformTransactionManagerByQualifier(beanFactory, qualifier)
            } ?: transactionManager.getIfUnique()
            val readOnlyRunner = ptm?.let { tm ->
                SpringTransactionRunner(TransactionTemplate(tm).apply { isReadOnly = true })
            }
            return MicrometerOutboxMetrics(
                store = store,
                registry = registry,
                transactionRunner = readOnlyRunner,
                clock = clock.getIfAvailable { Clock.systemUTC() },
            )
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        @ConditionalOnMissingBean
        fun outboxMetricsRefresher(metrics: MicrometerOutboxMetrics, properties: OkapiMetricsProperties): OutboxMetricsRefresher =
            OutboxMetricsRefresher(metrics, properties.refreshInterval)
    }

    /**
     * Logs a single startup warning when okapi's Micrometer auto-config is explicitly opted out
     * (`okapi.metrics.enabled=false`). Without this, the opt-out was previously silently ignored
     * entirely (issue #92).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "okapi.metrics", name = ["enabled"], havingValue = "false")
    class MetricsDisabledNotice {
        init {
            logger.warn(
                "okapi.metrics.enabled=false. Okapi will NOT register MicrometerOutboxListener, " +
                    "MicrometerOutboxMetrics, or OutboxMetricsRefresher. No okapi.* counters, timers, " +
                    "or gauges will be published, and the outbox store will not be polled for them.",
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OkapiMicrometerAutoConfiguration::class.java)
    }
}
