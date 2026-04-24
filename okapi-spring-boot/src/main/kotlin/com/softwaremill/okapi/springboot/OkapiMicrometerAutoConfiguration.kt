package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.micrometer.MicrometerOutboxListener
import com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics
import com.softwaremill.okapi.micrometer.OutboxMetricsRefresher
import io.micrometer.core.instrument.MeterRegistry
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
    name = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"],
)
@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
@ConditionalOnBean(MeterRegistry::class)
@EnableConfigurationProperties(OkapiMetricsProperties::class)
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
    ): MicrometerOutboxMetrics {
        val readOnlyRunner = transactionManager.getIfAvailable()?.let { tm ->
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
