package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.CompositeMessageDeliverer
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import liquibase.integration.spring.SpringLiquibase
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource

/**
 * Spring Boot autoconfiguration for the outbox processing pipeline.
 *
 * Required beans (must be provided by the application):
 * - One or more [MessageDeliverer] beans — transport implementations
 *   (e.g. HttpMessageDeliverer, KafkaMessageDeliverer).
 *   Multiple deliverers are automatically wrapped in [CompositeMessageDeliverer]
 *   and routed by the `type` field in each entry's deliveryMetadata.
 *
 * Optional beans with defaults:
 * - [OutboxStore] — auto-configured to [PostgresOutboxStore] if `outbox-postgres` is on the classpath
 * - [Clock] — defaults to [Clock.systemUTC]
 * - [RetryPolicy] — defaults to `maxRetries = 5`
 * - [PlatformTransactionManager] — if absent, each store call runs in its own transaction
 */
@AutoConfiguration
class OutboxAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun outboxPublisher(outboxStore: OutboxStore, clock: ObjectProvider<Clock>): OutboxPublisher {
        return OutboxPublisher(
            outboxStore = outboxStore,
            clock = clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun springOutboxPublisher(outboxPublisher: OutboxPublisher): SpringOutboxPublisher = SpringOutboxPublisher(delegate = outboxPublisher)

    @Bean
    @ConditionalOnMissingBean
    fun outboxEntryProcessor(
        deliverers: List<MessageDeliverer>,
        retryPolicy: ObjectProvider<RetryPolicy>,
        clock: ObjectProvider<Clock>,
    ): OutboxEntryProcessor {
        return OutboxEntryProcessor(
            deliverer = if (deliverers.size == 1) deliverers.single() else CompositeMessageDeliverer(deliverers),
            retryPolicy = retryPolicy.getIfAvailable { RetryPolicy(maxRetries = 5) },
            clock = clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun outboxProcessor(outboxStore: OutboxStore, outboxEntryProcessor: OutboxEntryProcessor): OutboxProcessor {
        return OutboxProcessor(
            store = outboxStore,
            entryProcessor = outboxEntryProcessor,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun outboxProcessorScheduler(
        outboxProcessor: OutboxProcessor,
        transactionManager: ObjectProvider<PlatformTransactionManager>,
    ): OutboxProcessorScheduler {
        return OutboxProcessorScheduler(
            outboxProcessor = outboxProcessor,
            transactionTemplate = transactionManager.getIfAvailable()?.let { TransactionTemplate(it) },
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "okapi.purger", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun outboxPurgerScheduler(outboxStore: OutboxStore, clock: ObjectProvider<Clock>): OutboxPurgerScheduler {
        return OutboxPurgerScheduler(
            outboxStore = outboxStore,
            clock = clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    /**
     * Auto-configures [PostgresOutboxStore] and Liquibase schema migration
     * when `outbox-postgres` is on the classpath.
     * Skipped if the application provides its own [OutboxStore] bean.
     */
    @Configuration
    @ConditionalOnClass(PostgresOutboxStore::class)
    class PostgresStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean(OutboxStore::class)
        fun outboxStore(clock: ObjectProvider<Clock>): OutboxStore = PostgresOutboxStore(clock = clock.getIfAvailable { Clock.systemUTC() })

        /**
         * Runs okapi Liquibase migrations automatically when both:
         * - `liquibase-core` is on the classpath (i.e. the application already uses Liquibase)
         * - a [DataSource] bean is available
         *
         * The migration is idempotent (CREATE TABLE IF NOT EXISTS) and uses a separate
         * Liquibase bean named `okapiLiquibase` to avoid conflicting with the application's
         * own Liquibase configuration.
         *
         * To opt out, define your own bean named `okapiLiquibase` or include the okapi
         * changelog manually in your master changelog:
         * `classpath:com/softwaremill/okapi/db/changelog.xml`
         */
        @Bean("okapiLiquibase")
        @ConditionalOnClass(SpringLiquibase::class)
        @ConditionalOnBean(DataSource::class)
        @ConditionalOnMissingBean(name = ["okapiLiquibase"])
        fun okapiLiquibase(dataSource: DataSource): SpringLiquibase = SpringLiquibase().apply {
            this.dataSource = dataSource
            changeLog = "classpath:com/softwaremill/okapi/db/changelog.xml"
        }
    }
}
