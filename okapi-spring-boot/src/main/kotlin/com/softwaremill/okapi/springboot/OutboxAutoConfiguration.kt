package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.CompositeMessageDeliverer
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxProcessorListener
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxPurgerConfig
import com.softwaremill.okapi.core.OutboxSchedulerConfig
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import liquibase.integration.spring.SpringLiquibase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource

private val LIQUIBASE_DISABLED_LOGGER = LoggerFactory.getLogger("com.softwaremill.okapi.springboot.OutboxAutoConfiguration")

/**
 * Spring Boot autoconfiguration for the outbox processing pipeline.
 *
 * Required beans (must be provided by the application):
 * - One or more [MessageDeliverer] beans — transport implementations
 *   (e.g. HttpMessageDeliverer, KafkaMessageDeliverer).
 *   Multiple deliverers are automatically wrapped in [CompositeMessageDeliverer]
 *   and routed by [OutboxEntry.deliveryType].
 *
 * Optional beans with defaults:
 * - [OutboxStore] — auto-configured to [PostgresOutboxStore] or [MysqlOutboxStore]
 *   depending on which module (`okapi-postgres` / `okapi-mysql`) is on the classpath.
 *   If both are present, Postgres takes priority. Override by defining your own `@Bean OutboxStore`.
 * - [Clock] — defaults to [Clock.systemUTC]
 * - [RetryPolicy] — defaults to `maxRetries = 5`
 * - [PlatformTransactionManager] — when present, scheduler/purger wrap each tick in a Spring
 *   transaction. When absent, store calls run in JDBC auto-commit mode, which narrows
 *   `FOR UPDATE SKIP LOCKED` to the claim itself and allows duplicate delivery across
 *   processor instances; configure one for any multi-instance deployment.
 *
 * Multi-datasource support:
 * - Set `okapi.datasource-qualifier` to the bean name of the [DataSource] that holds the outbox table.
 *   When not set, the primary (or single) DataSource is used.
 *
 * Liquibase coexistence:
 * - Auto-config is ordered after Spring Boot's `LiquibaseAutoConfiguration` (3.x and 4.x package
 *   paths covered) so that the application's own auto-configured `liquibase` bean registers first.
 *   Spring Boot's `@ConditionalOnMissingBean(SpringLiquibase::class)` then sees its own bean and
 *   stops looking, leaving okapi free to add its uniquely-named `okapiPostgresLiquibase` /
 *   `okapiMysqlLiquibase` next to it. Both run on startup with their own changelogs.
 * - Set `okapi.liquibase.enabled=false` to opt out entirely (e.g. when including okapi's
 *   changelog from the application's master changelog).
 */
@AutoConfiguration
@AutoConfigureAfter(
    name = [
        // Spring Boot 3.x — package path
        "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
        // Spring Boot 4.x — package was reorganized into a separate module
        "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration",
    ],
)
@EnableConfigurationProperties(OkapiProperties::class, OutboxPurgerProperties::class, OutboxProcessorProperties::class)
class OutboxAutoConfiguration(
    private val dataSources: Map<String, DataSource>,
    private val primaryDataSource: DataSource,
    private val okapiProperties: OkapiProperties,
) {
    private fun resolveDataSource(): DataSource = resolveDataSource(dataSources, primaryDataSource, okapiProperties)

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
    fun springOutboxPublisher(outboxPublisher: OutboxPublisher): SpringOutboxPublisher =
        SpringOutboxPublisher(delegate = outboxPublisher, dataSource = resolveDataSource())

    @Bean
    @ConditionalOnMissingBean
    fun outboxEntryProcessor(
        props: OutboxProcessorProperties,
        deliverers: List<MessageDeliverer>,
        retryPolicy: ObjectProvider<RetryPolicy>,
        clock: ObjectProvider<Clock>,
    ): OutboxEntryProcessor {
        return OutboxEntryProcessor(
            deliverer = if (deliverers.size == 1) deliverers.single() else CompositeMessageDeliverer(deliverers),
            retryPolicy = retryPolicy.getIfAvailable { RetryPolicy(maxRetries = props.maxRetries) },
            clock = clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun outboxProcessor(
        outboxStore: OutboxStore,
        outboxEntryProcessor: OutboxEntryProcessor,
        listener: ObjectProvider<OutboxProcessorListener>,
        clock: ObjectProvider<Clock>,
    ): OutboxProcessor {
        return OutboxProcessor(
            store = outboxStore,
            entryProcessor = outboxEntryProcessor,
            listener = listener.getIfAvailable(),
            clock = clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "okapi.processor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun outboxProcessorScheduler(
        props: OutboxProcessorProperties,
        outboxProcessor: OutboxProcessor,
        transactionManager: ObjectProvider<PlatformTransactionManager>,
    ): OutboxProcessorScheduler {
        return OutboxProcessorScheduler(
            outboxProcessor = outboxProcessor,
            transactionTemplate = transactionManager.getIfAvailable()?.let { TransactionTemplate(it) },
            config = OutboxSchedulerConfig(
                interval = props.interval,
                batchSize = props.batchSize,
            ),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "okapi.purger", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun outboxPurgerScheduler(
        props: OutboxPurgerProperties,
        outboxStore: OutboxStore,
        transactionManager: ObjectProvider<PlatformTransactionManager>,
        clock: ObjectProvider<Clock>,
    ): OutboxPurgerScheduler {
        return OutboxPurgerScheduler(
            outboxStore = outboxStore,
            transactionTemplate = transactionManager.getIfAvailable()?.let { TransactionTemplate(it) },
            config = OutboxPurgerConfig(
                retention = props.retention,
                interval = props.interval,
                batchSize = props.batchSize,
            ),
            clock = clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    /**
     * Auto-configures [PostgresOutboxStore] when `okapi-postgres` is on the classpath.
     * Skipped if the application provides its own [OutboxStore] bean.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(PostgresOutboxStore::class)
    class PostgresStoreConfiguration(
        private val dataSources: Map<String, DataSource>,
        private val primaryDataSource: DataSource,
        private val okapiProperties: OkapiProperties,
    ) {
        @Bean
        @ConditionalOnMissingBean(OutboxStore::class)
        fun outboxStore(clock: ObjectProvider<Clock>): PostgresOutboxStore = PostgresOutboxStore(
            connectionProvider = SpringConnectionProvider(resolveDataSource(dataSources, primaryDataSource, okapiProperties)),
            clock = clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    /** When both Postgres and MySQL modules are on the classpath, [PostgresStoreConfiguration] takes priority. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MysqlOutboxStore::class)
    class MysqlStoreConfiguration(
        private val dataSources: Map<String, DataSource>,
        private val primaryDataSource: DataSource,
        private val okapiProperties: OkapiProperties,
    ) {
        @Bean
        @ConditionalOnMissingBean(OutboxStore::class)
        fun outboxStore(clock: ObjectProvider<Clock>): MysqlOutboxStore = MysqlOutboxStore(
            connectionProvider = SpringConnectionProvider(resolveDataSource(dataSources, primaryDataSource, okapiProperties)),
            clock = clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    /**
     * Auto-configures okapi's PostgreSQL Liquibase migration.
     *
     * **Why a separate class with class-level [ConditionalOnClass]:** placing
     * `@ConditionalOnClass(SpringLiquibase::class)` on the class level (rather than on the
     * `@Bean` method) ensures Spring evaluates the condition via string-name classpath lookup
     * **before** any introspection of the class's methods. Without this, JVM
     * `Class.getDeclaredMethods()` would resolve [SpringLiquibase] in the method return type
     * during configuration parsing — which fails with `NoClassDefFoundError` whenever
     * `liquibase-core` is absent from the consumer's classpath (it is `compileOnly` in
     * okapi-spring-boot, e.g. Flyway-only consumers do not pull it in).
     *
     * **Coexistence with the host application's own [SpringLiquibase]:**
     * `@ConditionalOnMissingBean(SpringLiquibase::class)` is intentionally **not** used —
     * okapi's bean is named `okapiPostgresLiquibase` and runs its own bundled changelog with
     * dedicated tracking tables (`okapi_databasechangelog`/`okapi_databasechangeloglock` by
     * default). It coexists alongside Spring Boot's auto-configured `liquibase` bean. Disable
     * okapi's bean via `okapi.liquibase.enabled=false` if the host already includes okapi's
     * changelog from its own master.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SpringLiquibase::class, PostgresOutboxStore::class)
    @ConditionalOnProperty(prefix = "okapi.liquibase", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    class PostgresLiquibaseConfiguration(
        private val dataSources: Map<String, DataSource>,
        private val primaryDataSource: DataSource,
        private val okapiProperties: OkapiProperties,
    ) {
        @Bean("okapiPostgresLiquibase")
        @ConditionalOnMissingBean(name = ["okapiPostgresLiquibase"])
        fun okapiPostgresLiquibase(): SpringLiquibase = SpringLiquibase().apply {
            dataSource = resolveDataSource(dataSources, primaryDataSource, okapiProperties)
            changeLog = "classpath:com/softwaremill/okapi/db/changelog.xml"
            databaseChangeLogTable = okapiProperties.liquibase.changelogTable
            databaseChangeLogLockTable = okapiProperties.liquibase.changelogLockTable
        }
    }

    /**
     * Logs a single startup warning when okapi's Liquibase auto-config is explicitly opted out
     * (`okapi.liquibase.enabled=false`). Without this, a user who flipped the flag months ago
     * has no breadcrumb when they later see "relation okapi_outbox does not exist" at first
     * publish — the link to the opt-out is in the startup log, not the error.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "okapi.liquibase", name = ["enabled"], havingValue = "false")
    class LiquibaseDisabledNotice {
        init {
            LIQUIBASE_DISABLED_LOGGER.warn(
                "okapi.liquibase.enabled=false — okapi will NOT create or migrate the okapi_outbox schema. " +
                    "Ensure your application's migration tool applies " +
                    "classpath:com/softwaremill/okapi/db/changelog.xml " +
                    "(or classpath:com/softwaremill/okapi/db/mysql/changelog.xml for MySQL).",
            )
        }
    }

    /**
     * Auto-configures okapi's MySQL Liquibase migration. See [PostgresLiquibaseConfiguration]
     * for rationale on the class-level conditional placement.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SpringLiquibase::class, MysqlOutboxStore::class)
    @ConditionalOnProperty(prefix = "okapi.liquibase", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    class MysqlLiquibaseConfiguration(
        private val dataSources: Map<String, DataSource>,
        private val primaryDataSource: DataSource,
        private val okapiProperties: OkapiProperties,
    ) {
        @Bean("okapiMysqlLiquibase")
        @ConditionalOnMissingBean(name = ["okapiMysqlLiquibase"])
        fun okapiMysqlLiquibase(): SpringLiquibase = SpringLiquibase().apply {
            dataSource = resolveDataSource(dataSources, primaryDataSource, okapiProperties)
            changeLog = "classpath:com/softwaremill/okapi/db/mysql/changelog.xml"
            databaseChangeLogTable = okapiProperties.liquibase.changelogTable
            databaseChangeLogLockTable = okapiProperties.liquibase.changelogLockTable
        }
    }

    companion object {
        internal fun resolveDataSource(
            dataSources: Map<String, DataSource>,
            primaryDataSource: DataSource,
            okapiProperties: OkapiProperties,
        ): DataSource {
            val qualifier = okapiProperties.datasourceQualifier
                ?: return primaryDataSource
            return dataSources[qualifier]
                ?: error(
                    "okapi.datasource-qualifier='$qualifier' — no DataSource bean named '$qualifier' found. " +
                        "Available: ${dataSources.keys}",
                )
        }
    }
}
