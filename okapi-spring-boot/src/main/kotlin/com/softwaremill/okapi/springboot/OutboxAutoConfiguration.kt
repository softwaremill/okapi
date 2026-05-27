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
import java.util.Collections
import java.util.IdentityHashMap
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
    // Skipping the bean in publish-only deployments (both schedulers disabled) lets users omit a
    // PlatformTransactionManager entirely (e.g. a pure producer delegating processing to a worker).
    //
    // We accept a user-defined TransactionTemplate OR Boot's auto-registered one transparently, but
    // always extract the PTM and run validatePtmDataSourceMatch — so the user's TX semantics are
    // honoured AND the multi-DS safety net stays armed (see TransactionTemplateHijackProofTest).
    //
    // Return type is `TransactionRunner` (interface), not `SpringTransactionRunner` (concrete) —
    // deliberate deviation from the "@Bean returns concrete type" convention. @ConditionalOnMissingBean
    // matches against the declared return type, so declaring the interface lets any user-supplied
    // TransactionRunner impl suppress this factory; the concrete type would miss custom impls.
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("\${okapi.processor.enabled:true} or \${okapi.purger.enabled:true}")
    fun okapiTransactionRunner(
        transactionManager: ObjectProvider<PlatformTransactionManager>,
        transactionTemplate: ObjectProvider<TransactionTemplate>,
        beanFactory: BeanFactory,
    ): TransactionRunner {
        val anyTemplate = transactionTemplate.getIfUnique()
        // PTM resolution precedence:
        //  - `okapi.transaction-manager-qualifier` set → resolve via qualifier (user explicit wins
        //    over any auto-wired TT). Without this, Spring Boot's TransactionAutoConfiguration
        //    auto-registers a TT around @Primary PTM, `anyTemplate.transactionManager` would
        //    short-circuit, and the qualifier would be silently ignored in every typical Spring
        //    Boot app.
        //  - qualifier unset → take PTM from any unique TT in context (user-defined or Boot's
        //    auto-TT — preserves their TX semantics). The `?:` is a defensive guard for non-Spring
        //    callers; Spring rejects a TT with null transactionManager at afterPropertiesSet().
        val ptm = if (okapiProperties.transactionManagerQualifier != null) {
            resolvePlatformTransactionManager(transactionManager, beanFactory, okapiProperties)
        } else {
            anyTemplate?.transactionManager
                ?: resolvePlatformTransactionManager(transactionManager, beanFactory, okapiProperties)
        }
        validatePtmDataSourceMatch(ptm, resolveDataSource(), okapiProperties, dataSources.size)
        // Use the supplied TT verbatim ONLY when it actually wraps the PTM we picked (preserves
        // timeout/propagation/isolation). When qualifier forced a different PTM, build a fresh TT
        // around it — otherwise we'd run on a TT bound to the wrong PTM. NOTE: with
        // PROPAGATION_REQUIRED a tick joining an outer readOnly=true TX inherits that flag
        // silently — keep scheduler invocations outside @Transactional scopes to avoid FOR UPDATE
        // SKIP LOCKED failures.
        val ttToUse = if (anyTemplate != null && anyTemplate.transactionManager === ptm) {
            anyTemplate
        } else {
            TransactionTemplate(ptm).apply { isReadOnly = false }
        }
        return SpringTransactionRunner(ttToUse)
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
            dataSourceBeanCount: Int,
        ) {
            // extractDataSource tries ResourceTransactionManager, then JpaTransactionManager /
            // HibernateTransactionManager via reflection. Non-extractable PTMs (JTA, Exposed
            // SpringTransactionManager, JPA without a JDBC DS) fall through to the multi-DS
            // ambiguity guard: ≥2 DataSource beans + no qualifier → refuse to start.
            val ptmDataSource = extractDataSource(ptm)
            if (ptmDataSource != null) {
                // Spring docs say TransactionAwareDataSourceProxy must NOT be passed to a PTM, so
                // the PTM holds the raw DS while helpers hold the proxy. Unwrap both chains before
                // identity comparison to avoid false mismatch on wrapper/raw pairs.
                val unwrappedPtm = unwrapDataSource(ptmDataSource)
                val unwrappedOutbox = unwrapDataSource(outboxDataSource)
                when {
                    unwrappedPtm is Unwrapped.Unresolvable || unwrappedOutbox is Unwrapped.Unresolvable -> {
                        // Chain didn't reach a concrete DS — report as wiring error, not a mismatch.
                        val ptmSide = describeUnwrap("PTM", ptmDataSource, unwrappedPtm)
                        val outboxSide = describeUnwrap("outbox", outboxDataSource, unwrappedOutbox)
                        error(
                            "Could not verify the PTM↔DataSource binding: a DelegatingDataSource chain " +
                                "terminated before reaching a concrete backing DataSource. $ptmSide; $outboxSide. " +
                                "Fix the proxy wiring (cycle in setTargetDataSource, or initialise the lazy " +
                                "proxy's targetDataSource before context refresh), or define an explicit " +
                                "@Bean TransactionRunner.",
                        )
                    }
                    (unwrappedPtm as Unwrapped.Resolved).ds !== (unwrappedOutbox as Unwrapped.Resolved).ds -> {
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
                }
                return
            }
            val resourceFactoryDescription = describeUnextractable(ptm)
            // Multi-DS ambiguity guard: if okapi cannot extract the PTM's DataSource AND there are
            // ≥2 DataSource beans AND no transaction-manager-qualifier was set, refuse to start.
            // okapi.datasource-qualifier alone is insufficient — it picks the outbox DS but does
            // NOT constrain which PTM brackets it. A wrong-DS PTM would collapse FOR UPDATE SKIP
            // LOCKED to JDBC auto-commit and silently permit duplicate delivery. Opt out by setting
            // okapi.transaction-manager-qualifier or supplying an explicit @Bean TransactionRunner.
            if (dataSourceBeanCount >= 2 && properties.transactionManagerQualifier == null) {
                error(
                    "Cannot verify the PTM↔DataSource binding for a non-extractable " +
                        "PlatformTransactionManager '${ptm.javaClass.name}' ($resourceFactoryDescription) " +
                        "in a multi-DataSource context ($dataSourceBeanCount DataSource beans found). " +
                        "okapi.transaction-manager-qualifier is not set, so okapi cannot determine which " +
                        "DataSource this PTM brackets — if it brackets the wrong one, FOR UPDATE SKIP " +
                        "LOCKED collapses to JDBC auto-commit and scheduler ticks silently allow " +
                        "duplicate delivery across processor instances. (okapi.datasource-qualifier alone " +
                        "is not sufficient: it picks the outbox DataSource but does not constrain which " +
                        "PTM brackets it.) Fix: set okapi.transaction-manager-qualifier to name the PTM " +
                        "that brackets the outbox DataSource, or define an explicit @Bean TransactionRunner.",
                )
            }
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
                // Single-DS assumption. Emit an INFO breadcrumb so a future multi-DS migration that
                // forgets to set the qualifier produces something to grep for when debugging delivery.
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

        /** Result of walking a [DelegatingDataSource] chain to its concrete backing DataSource. */
        sealed interface Unwrapped {
            data class Resolved(val ds: DataSource) : Unwrapped

            /**
             * Unwrap stopped before reaching a concrete backing DataSource. Identity comparison would
             * be inconclusive — callers must NOT treat this as a mismatch.
             */
            data class Unresolvable(val stoppedAt: DataSource, val reason: Reason) : Unwrapped

            enum class Reason { CYCLE, NULL_TARGET }
        }

        // Iterative walk with an IdentityHashMap visited-set: guards against cyclic chains
        // (Spring's setTargetDataSource has no cycle check). Identity, not equals(), because a
        // custom DS overriding equals() to delegate to its target could trigger false early
        // termination on a valid chain.
        internal fun unwrapDataSource(ds: DataSource): Unwrapped {
            val seen: MutableSet<DataSource> = Collections.newSetFromMap(IdentityHashMap())
            var current: DataSource = ds
            while (current is DelegatingDataSource) {
                if (!seen.add(current)) return Unwrapped.Unresolvable(current, Unwrapped.Reason.CYCLE)
                val target = current.targetDataSource
                    ?: return Unwrapped.Unresolvable(current, Unwrapped.Reason.NULL_TARGET)
                current = target
            }
            return Unwrapped.Resolved(current)
        }

        private fun describeUnwrap(side: String, original: DataSource, unwrapped: Unwrapped): String = when (unwrapped) {
            is Unwrapped.Resolved -> "$side side: $original resolved to ${unwrapped.ds}"
            is Unwrapped.Unresolvable ->
                "$side side: $original stopped at ${unwrapped.stoppedAt} (${unwrapped.reason})"
        }

        // JPA/Hibernate PTMs that expose a `public DataSource getDataSource()` — reflection by name
        // avoids requiring spring-orm on the compile classpath (it's optional for JDBC-only consumers).
        // Hibernate's TM is `org.springframework.orm.jpa.hibernate.HibernateTransactionManager` in
        // Spring 6.2+ and `org.springframework.orm.hibernate5.HibernateTransactionManager` in 6.1-;
        // both are listed so a single build works across versions without a matrix.
        // Public so the cross-module reflection-resolution guard test (in okapi-integration-tests,
        // where spring-orm is available) can verify the set isn't entirely stale.
        val JPA_HIBERNATE_PTM_CLASSES = setOf(
            "org.springframework.orm.jpa.JpaTransactionManager",
            "org.springframework.orm.jpa.hibernate.HibernateTransactionManager",
            "org.springframework.orm.hibernate5.HibernateTransactionManager",
        )

        // Walk the superclass chain (not just `ptm.javaClass.name`) so user-defined subclasses
        // of `JpaTransactionManager` / `HibernateTransactionManager` are also recognised. Reads
        // `.name` / `.superclass` on the already-loaded hierarchy only — no `Class.forName`, so
        // the "no hard spring-orm compile dependency" constraint is preserved.
        private fun isJpaHibernatePtm(ptm: PlatformTransactionManager): Boolean {
            var c: Class<*>? = ptm.javaClass
            while (c != null) {
                if (c.name in JPA_HIBERNATE_PTM_CLASSES) return true
                c = c.superclass
            }
            return false
        }

        internal fun extractDataSource(ptm: PlatformTransactionManager): DataSource? {
            (ptm as? ResourceTransactionManager)?.resourceFactory?.let { rf ->
                if (rf is DataSource) return rf
            }
            // Narrow catch on NoSuchMethodException only — all other exceptions propagate.
            // Do NOT use runCatching: it swallows LinkageError/NoClassDefFoundError (mixed-jar
            // classpath bug), InvocationTargetException (PTM built without an EMF), and
            // IllegalAccessException/ClassCastException (incompatible Spring version).
            if (isJpaHibernatePtm(ptm)) {
                return try {
                    ptm.javaClass.getMethod("getDataSource").invoke(ptm) as DataSource?
                } catch (_: NoSuchMethodException) {
                    null
                }
            }
            return null
        }

        private fun describeUnextractable(ptm: PlatformTransactionManager): String {
            if (isJpaHibernatePtm(ptm)) {
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
