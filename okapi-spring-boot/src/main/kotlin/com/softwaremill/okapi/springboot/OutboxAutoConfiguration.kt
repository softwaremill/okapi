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
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanNotOfRequiredTypeException
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.ResourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource

/**
 * Spring Boot autoconfiguration for the outbox processing pipeline.
 *
 * Requires a [TransactionRunner] bean, or a [PlatformTransactionManager] from which one can be derived.
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
 *
 * Multi-datasource / multi-PlatformTransactionManager support:
 * - Set `okapi.datasource-qualifier` to the bean name of the [DataSource] that holds the outbox table.
 *   When not set, the primary (or single) DataSource is used.
 * - Set `okapi.transaction-manager-qualifier` to the bean name of the
 *   [PlatformTransactionManager] that brackets the outbox DataSource. When not set, the unique
 *   (or `@Primary`) PTM is used. With multi-DS setups always set this explicitly — silent
 *   PTM↔DataSource mismatch otherwise reduces `FOR UPDATE SKIP LOCKED` to JDBC auto-commit and
 *   permits duplicate delivery across processor instances.
 *
 * Liquibase support is provided by [OkapiLiquibaseAutoConfiguration], which is ordered after this
 * auto-config so that its `@ConditionalOnBean(<X>OutboxStore::class)` gates can observe which
 * store bean actually won precedence.
 */
@AutoConfiguration
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

    // Created only when at least one scheduler is enabled — TransactionRunner has no other consumer.
    // Skipping the bean in publish-only deployments (both schedulers disabled) lets users run without
    // a PlatformTransactionManager on the classpath at all (e.g. message producer that delegates
    // outbox processing to a separate worker).
    //
    // ObjectProvider<TransactionTemplate> design: a TT in the context can come from two sources —
    // a user-defined @Bean (with custom timeout/propagation/isolation), OR Spring Boot's
    // TransactionAutoConfiguration which auto-registers a TT whenever a single PTM exists. The two
    // are indistinguishable at this layer. We accept BOTH transparently but always extract the bound
    // PTM and run validatePtmDataSourceMatch on it — so the user's TX semantics are honoured AND the
    // multi-DS safety net stays armed. See TransactionTemplateHijackProofTest for the empirical proof
    // that without `validatePtmDataSourceMatch` here, Boot's auto-TT silently bypasses safety.
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("\${okapi.processor.enabled:true} or \${okapi.purger.enabled:true}")
    fun okapiTransactionRunner(
        transactionManager: ObjectProvider<PlatformTransactionManager>,
        transactionTemplate: ObjectProvider<TransactionTemplate>,
        beanFactory: BeanFactory,
    ): TransactionRunner {
        val anyTemplate = transactionTemplate.getIfUnique()
        // Extract the PTM from the TT if available, else resolve through the provider. TT's
        // transactionManager is nullable in the API (a TT can be constructed without one) — fall
        // back to the provider path in that pathological case so we still get a validated PTM.
        val ptm = anyTemplate?.transactionManager
            ?: resolvePlatformTransactionManager(transactionManager, beanFactory, okapiProperties)
        validatePtmDataSourceMatch(ptm, resolveDataSource(), okapiProperties)
        // Use the user-/Boot-supplied TT verbatim when present (preserves timeout, propagation,
        // isolation set by whoever defined it). Otherwise build a minimal TT around the validated
        // PTM. Sets the *initial* TX read-only flag to false. NOTE: with PROPAGATION_REQUIRED
        // (the default), a tick that joins an outer @Transactional(readOnly = true) inherits the
        // outer's flag and this setting is silently ignored. The scheduler runs on a daemon
        // thread with no outer TX, so this flag actually takes effect — but invocations from
        // inside an existing read-only TX would still hit FOR UPDATE failures. Keep scheduler
        // invocations outside @Transactional scopes.
        return SpringTransactionRunner(
            anyTemplate ?: TransactionTemplate(ptm).apply { isReadOnly = false },
        )
    }

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
        transactionRunner: TransactionRunner,
    ): OutboxProcessorScheduler {
        return OutboxProcessorScheduler(
            outboxProcessor = outboxProcessor,
            transactionRunner = transactionRunner,
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
        transactionRunner: TransactionRunner,
        clock: ObjectProvider<Clock>,
    ): OutboxPurgerScheduler {
        return OutboxPurgerScheduler(
            outboxStore = outboxStore,
            transactionRunner = transactionRunner,
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

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxAutoConfiguration::class.java)

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

        internal fun resolvePlatformTransactionManager(
            provider: ObjectProvider<PlatformTransactionManager>,
            beanFactory: BeanFactory,
            properties: OkapiProperties,
        ): PlatformTransactionManager {
            val qualifier = properties.transactionManagerQualifier
            if (qualifier != null) {
                return try {
                    beanFactory.getBean(qualifier, PlatformTransactionManager::class.java)
                } catch (e: NoSuchBeanDefinitionException) {
                    throw NoSuchBeanDefinitionException(
                        qualifier,
                        "okapi.transaction-manager-qualifier='$qualifier' — no PlatformTransactionManager bean named " +
                            "'$qualifier' found. Check the bean name or remove the property to fall back to " +
                            "auto-resolution.",
                    ).apply { initCause(e) }
                } catch (e: BeanNotOfRequiredTypeException) {
                    // Common typo: qualifier points to e.g. a DataSource bean name instead of a PTM
                    // bean name. Spring's default message ("Bean named 'X' is expected to be of type ...
                    // but was actually of type ...") doesn't mention okapi, so users searching for
                    // "okapi" in startup logs find nothing. Rewrap with okapi-specific context.
                    throw IllegalStateException(
                        "okapi.transaction-manager-qualifier='$qualifier' — bean named '$qualifier' exists " +
                            "but is of type '${e.actualType.name}', not a PlatformTransactionManager. Check " +
                            "the property value (likely a typo into a DataSource or other bean name) or " +
                            "remove it to fall back to auto-resolution.",
                        e,
                    )
                }
            }
            val unique = provider.getIfUnique()
            if (unique != null) return unique
            val available = provider.stream().toList()
            throw NoSuchBeanDefinitionException(
                TransactionRunner::class.java,
                if (available.isEmpty()) {
                    "okapi-spring-boot requires a TransactionRunner bean to bracket each scheduler tick in a " +
                        "transaction. Configure spring-boot-starter-jdbc or spring-boot-starter-data-jpa (which " +
                        "provide a PlatformTransactionManager that okapi adapts automatically), or define your own " +
                        "@Bean TransactionRunner."
                } else {
                    "Multiple PlatformTransactionManager beans found (${available.size}), none marked as @Primary. " +
                        "Mark the outbox PTM as @Primary, set okapi.transaction-manager-qualifier to disambiguate, " +
                        "or define an explicit @Bean TransactionRunner."
                },
            )
        }

        internal fun validatePtmDataSourceMatch(
            ptm: PlatformTransactionManager,
            outboxDataSource: DataSource,
            properties: OkapiProperties,
        ) {
            // Tries three extraction strategies in order:
            //   1. ResourceTransactionManager whose resourceFactory is a JDBC DataSource (DST + custom)
            //   2. JpaTransactionManager.getDataSource() — public getter, autodetected from EntityManagerFactory
            //   3. HibernateTransactionManager.getDataSource() — same shape (both pre-/post-6.2 packages)
            // Non-extractable cases (JTA, Exposed SpringTransactionManager, JPA/Hibernate with no JDBC DS)
            // fall through to the WARN/INFO path. The WrongPtmDataSourceAmplificationProofTest empirically
            // shows that wrong-DS bracketing collapses FOR UPDATE SKIP LOCKED to JDBC auto-commit and
            // permits 100% delivery amplification — fail-fast when we can verify, log loudly when we cannot.
            val ptmDataSource = extractDataSource(ptm)
            if (ptmDataSource != null) {
                // Spring's recommended pattern wraps the outbox DataSource bean in TransactionAwareDataSourceProxy
                // (or LazyConnectionDataSourceProxy) for use by query helpers, while passing the raw DataSource
                // to the PTM (Spring docs explicitly say "TransactionAwareDataSourceProxy should NOT be passed
                // to a PTM"). Reference equality on those references would falsely fail. Unwrap the
                // DelegatingDataSource chain on both sides before comparison.
                val unwrappedPtm = unwrapDataSource(ptmDataSource)
                val unwrappedOutbox = unwrapDataSource(outboxDataSource)
                if (unwrappedPtm !== unwrappedOutbox) {
                    // If either side is still a DelegatingDataSource after unwrap, the chain terminated
                    // early — either a cycle (`setTargetDataSource(self)`) or a not-yet-initialised
                    // `LazyConnectionDataSourceProxy.targetDataSource == null`. Surface that as a distinct
                    // WARN so the operator looks at the proxy wiring instead of chasing the PTM↔DS error.
                    if (unwrappedPtm is DelegatingDataSource || unwrappedOutbox is DelegatingDataSource) {
                        logger.warn(
                            "Could not fully unwrap one or both DataSource sides — at least one " +
                                "DelegatingDataSource chain terminated early (cycle, or " +
                                "LazyConnectionDataSourceProxy with targetDataSource not yet set). " +
                                "PTM side: {} (stopped at {}). Outbox side: {} (stopped at {}). If the " +
                                "two are intended to wrap the same DataSource, fix the proxy chain " +
                                "before relying on the PTM↔DataSource mismatch error below.",
                            ptmDataSource,
                            unwrappedPtm,
                            outboxDataSource,
                            unwrappedOutbox,
                        )
                    }
                    error(
                        "PlatformTransactionManager '${ptm.javaClass.name}' is bound to a different DataSource than " +
                            "okapi's outbox DataSource. PTM DataSource: $ptmDataSource. Outbox DataSource: " +
                            "$outboxDataSource (resolved via okapi.datasource-qualifier=" +
                            "'${properties.datasourceQualifier ?: "<unset>"}'). Each scheduler tick would otherwise " +
                            "wrap a transaction on the wrong DataSource and FOR UPDATE SKIP LOCKED would collapse " +
                            "to JDBC auto-commit, allowing duplicate delivery. Fix: set okapi.transaction-manager-" +
                            "qualifier to point at the PTM that brackets the outbox DataSource, or define an explicit " +
                            "@Bean TransactionRunner.",
                    )
                }
                return
            }
            val resourceFactoryDescription = describeUnextractable(ptm)
            if (properties.datasourceQualifier != null) {
                logger.warn(
                    "okapi.datasource-qualifier='{}' is set, but the resolved PlatformTransactionManager '{}' {} " +
                        "— okapi cannot verify it brackets the outbox DataSource. If the PTM is bound to a different " +
                        "DataSource, scheduler ticks will silently run in JDBC auto-commit mode and FOR UPDATE SKIP " +
                        "LOCKED will collapse, allowing duplicate delivery across processor instances. Set " +
                        "okapi.transaction-manager-qualifier to disambiguate, or define an explicit @Bean " +
                        "TransactionRunner.",
                    properties.datasourceQualifier,
                    ptm.javaClass.name,
                    resourceFactoryDescription,
                )
            } else {
                // No qualifier set → single-DS assumption. We cannot validate, but a future multi-DS
                // migration that forgets to set the qualifier would silently break with no diagnostic.
                // Emit an INFO breadcrumb so an operator debugging duplicate delivery has something
                // to grep for.
                logger.info(
                    "PlatformTransactionManager '{}' {} — okapi cannot verify it brackets the outbox " +
                        "DataSource. Assuming single-DataSource setup (okapi.datasource-qualifier is " +
                        "unset). If you have or add multiple DataSources, set okapi.transaction-manager-" +
                        "qualifier (and okapi.datasource-qualifier) explicitly to avoid silent " +
                        "PTM↔DataSource mismatch.",
                    ptm.javaClass.name,
                    resourceFactoryDescription,
                )
            }
        }

        // Iterative + visited-set: defends against self-referencing or cyclic DelegatingDataSource
        // chains (Spring's `setTargetDataSource` is a public setter with no cycle check, so
        // misconfiguration like `proxy.setTargetDataSource(proxy)` is legal API). A tailrec form
        // would compile to an uninterruptible JVM goto loop and silently spin at startup.
        internal fun unwrapDataSource(ds: DataSource): DataSource {
            val seen = mutableSetOf<DataSource>()
            var current: DataSource = ds
            while (current is DelegatingDataSource) {
                if (!seen.add(current)) return current
                current = current.targetDataSource ?: return current
            }
            return current
        }

        // JPA/Hibernate PTMs that expose a `public DataSource getDataSource()` — reflection by name
        // avoids requiring spring-orm on the compile classpath (it's optional for JDBC-only consumers).
        // Hibernate's TM moved package in Spring 6.2 — both names are listed so a single okapi build
        // works against Spring Framework 6.1.x and 6.2+ without a version matrix.
        // Hibernate's TM is named `org.springframework.orm.jpa.hibernate.HibernateTransactionManager`
        // in Spring 6.2+ and `org.springframework.orm.hibernate5.HibernateTransactionManager` in
        // Spring 6.1-. Both are listed so a single build works across versions.
        // `internal` so reflection-resolution tests can verify the set isn't all stale.
        internal val JPA_HIBERNATE_PTM_CLASSES = setOf(
            "org.springframework.orm.jpa.JpaTransactionManager",
            "org.springframework.orm.jpa.hibernate.HibernateTransactionManager",
            "org.springframework.orm.hibernate5.HibernateTransactionManager",
        )

        internal fun extractDataSource(ptm: PlatformTransactionManager): DataSource? {
            // Strategy 1: ResourceTransactionManager (DST + any RTM whose resourceFactory is a DataSource).
            (ptm as? ResourceTransactionManager)?.resourceFactory?.let { rf ->
                if (rf is DataSource) return rf
            }
            // Strategy 2/3: JPA/Hibernate public `getDataSource()`. Narrow catch on
            // NoSuchMethodException only — all other exceptions intentionally propagate:
            //   LinkageError / NoClassDefFoundError → mixed-jar classpath bug, must surface (this
            //     is why we don't use `runCatching`, which would swallow them).
            //   InvocationTargetException → JpaTransactionManager constructed without an EMF;
            //     callable, but unusable for outbox bracketing — surface the original cause.
            //   IllegalAccessException / ClassCastException → incompatible Spring framework
            //     version where the contract has changed; not safe to silently fall back.
            if (ptm.javaClass.name in JPA_HIBERNATE_PTM_CLASSES) {
                return try {
                    ptm.javaClass.getMethod("getDataSource").invoke(ptm) as DataSource?
                } catch (_: NoSuchMethodException) {
                    null
                }
            }
            return null
        }

        private fun describeUnextractable(ptm: PlatformTransactionManager): String {
            if (ptm.javaClass.name in JPA_HIBERNATE_PTM_CLASSES) {
                return "is ${ptm.javaClass.name} but its getDataSource() returned null — the " +
                    "EntityManagerFactory/SessionFactory was constructed without a JDBC DataSource " +
                    "(typical for pure-JTA / JNDI-only setups). okapi cannot verify the binding"
            }
            val rtmResourceFactory = (ptm as? ResourceTransactionManager)?.resourceFactory
            return when {
                ptm !is ResourceTransactionManager ->
                    "does not implement ResourceTransactionManager (no resource factory exposed; same shape as " +
                        "JtaTransactionManager or Exposed's SpringTransactionManager)"
                rtmResourceFactory == null ->
                    "implements ResourceTransactionManager but its getResourceFactory() returned null"
                else ->
                    "implements ResourceTransactionManager but its resourceFactory is of type " +
                        "'${rtmResourceFactory.javaClass.name}', not a JDBC DataSource"
            }
        }
    }
}
