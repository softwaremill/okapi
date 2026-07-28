package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.micrometer.MicrometerOutboxListener
import com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics
import com.softwaremill.okapi.micrometer.OutboxMetricsRefresher
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
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
@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
@ConditionalOnBean(MeterRegistry::class)
@EnableConfigurationProperties(OkapiMetricsProperties::class, OkapiProperties::class)
class OkapiMicrometerAutoConfiguration {
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
