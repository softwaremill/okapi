package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.micrometer.MicrometerOutboxListener
import com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics
import com.softwaremill.okapi.micrometer.OutboxMetricsRefresher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.h2.jdbcx.JdbcDataSource
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.GenericApplicationContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import javax.sql.DataSource

/**
 * Regression coverage for issue #80: `micrometerOutboxMetrics()` used a bare
 * `ObjectProvider<PlatformTransactionManager>.getIfAvailable()`, which throws
 * `NoUniqueBeanDefinitionException` whenever 2+ PlatformTransactionManager beans are present —
 * regardless of `okapi.transaction-manager-qualifier`. The fix reuses
 * [OutboxAutoConfiguration.resolvePlatformTransactionManagerByQualifier], the same qualifier
 * resolution [OutboxAutoConfiguration] uses for its (required) PTM lookup.
 *
 * Also covers the `okapi-micrometer`-missing-from-the-classpath bug: `MeterRegistry` alone
 * (`@ConditionalOnClass`'s original guard) is not evidence `okapi-micrometer` is present — plenty
 * of apps have `MeterRegistry` on the classpath via Spring Boot Actuator without ever adding
 * `okapi-micrometer`. [OkapiMicrometerAutoConfiguration] directly references
 * [MicrometerOutboxListener] et al., so on such a classpath the class must be skipped by the
 * class-level `@ConditionalOnClass`, not merely have individual `@Bean` methods fail.
 */
class OkapiMicrometerAutoConfigurationTest : FunSpec({

    val baseRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OkapiMicrometerAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })

    test("BUG #80 regression: two PlatformTransactionManager beans, none @Primary, no qualifier — context still starts") {
        // Pre-fix, this scenario always threw NoUniqueBeanDefinitionException from
        // ObjectProvider.getIfAvailable() inside micrometerOutboxMetrics().
        baseRunner
            .withBean("tm1", PlatformTransactionManager::class.java, { DataSourceTransactionManager(h2DataSource()) })
            .withBean("tm2", PlatformTransactionManager::class.java, { DataSourceTransactionManager(h2DataSource()) })
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                ctx.getBean(MicrometerOutboxMetrics::class.java).shouldNotBeNull()
            }
    }

    test("okapi.transaction-manager-qualifier avoids the BUG #80 crash when multiple PTMs are present") {
        baseRunner
            .withBean("tm1", PlatformTransactionManager::class.java, { DataSourceTransactionManager(h2DataSource()) })
            .withBean("tm2", PlatformTransactionManager::class.java, { DataSourceTransactionManager(h2DataSource()) })
            .withPropertyValues("okapi.transaction-manager-qualifier=tm2")
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                ctx.getBean(MicrometerOutboxMetrics::class.java).shouldNotBeNull()
            }
    }

    test("okapi.transaction-manager-qualifier pointing to a nonexistent bean fails fast with actionable message") {
        // Proves the shared resolver (and its error wrapping) is actually reused here, not
        // reimplemented — same message shape as OutboxAutoConfigurationTransactionRunnerTest.
        baseRunner
            .withBean("tm1", PlatformTransactionManager::class.java, { DataSourceTransactionManager(h2DataSource()) })
            .withBean("tm2", PlatformTransactionManager::class.java, { DataSourceTransactionManager(h2DataSource()) })
            .withPropertyValues("okapi.transaction-manager-qualifier=missingTm")
            .run { ctx ->
                val failure = ctx.startupFailure
                failure.shouldNotBeNull()
                val chain = generateSequence(failure as Throwable?) { it.cause }.toList()
                chain.any { it is NoSuchBeanDefinitionException } shouldBe true
                val allMessages = chain.mapNotNull { it.message }
                allMessages.any { it.contains("okapi.transaction-manager-qualifier") } shouldBe true
                allMessages.any { it.contains("missingTm") } shouldBe true
            }
    }

    test("no PlatformTransactionManager present: MicrometerOutboxMetrics bean is still created (PTM remains optional)") {
        baseRunner.run { ctx ->
            ctx.startupFailure.shouldBeNull()
            ctx.getBean(MicrometerOutboxMetrics::class.java).shouldNotBeNull()
        }
    }

    // The tests below call the @Bean factory method directly against a plain (non-autoconfigured)
    // GenericApplicationContext, bypassing OkapiMicrometerAutoConfiguration's `outboxMetricsRefresher`
    // bean entirely. That bean starts a background thread that calls `refresh()` immediately, which
    // would race with the assertions below (both threads reading/binding transactional resources on
    // the same DataSource). Driving the factory method directly keeps these deterministic while still
    // proving *which* PlatformTransactionManager backs the resulting read-only TransactionRunner.
    context("PlatformTransactionManager selection (verified via TransactionSynchronizationManager)") {

        test("qualifier selects the correct PlatformTransactionManager among several (not merely avoids the crash)") {
            val ds1: DataSource = h2DataSource()
            val ds2: DataSource = h2DataSource()
            val beanCtx = GenericApplicationContext().apply {
                registerBean<PlatformTransactionManager>("tm1") { DataSourceTransactionManager(ds1) }
                registerBean<PlatformTransactionManager>("tm2") { DataSourceTransactionManager(ds2) }
                refresh()
            }
            var ds1Bound: Boolean? = null
            var ds2Bound: Boolean? = null
            val store = object : OutboxStore by stubStore() {
                override fun countByStatuses(): Map<OutboxStatus, Long> {
                    ds1Bound = TransactionSynchronizationManager.getResource(ds1) != null
                    ds2Bound = TransactionSynchronizationManager.getResource(ds2) != null
                    return emptyMap()
                }
            }
            val metrics = OkapiMicrometerAutoConfiguration().micrometerOutboxMetrics(
                store = store,
                registry = SimpleMeterRegistry(),
                transactionManager = beanCtx.beanFactory.getBeanProvider(PlatformTransactionManager::class.java),
                clock = beanCtx.beanFactory.getBeanProvider(Clock::class.java),
                beanFactory = beanCtx.beanFactory,
                okapiProperties = OkapiProperties(transactionManagerQualifier = "tm2"),
            )
            metrics.refresh()
            ds1Bound shouldBe false
            ds2Bound shouldBe true
        }

        test(
            "no qualifier + single PlatformTransactionManager: it is used for the read-only snapshot (baseline preserved by the refactor)",
        ) {
            val ds: DataSource = h2DataSource()
            val beanCtx = GenericApplicationContext().apply {
                registerBean<PlatformTransactionManager>("onlyTm") { DataSourceTransactionManager(ds) }
                refresh()
            }
            var dsBound: Boolean? = null
            val store = object : OutboxStore by stubStore() {
                override fun countByStatuses(): Map<OutboxStatus, Long> {
                    dsBound = TransactionSynchronizationManager.getResource(ds) != null
                    return emptyMap()
                }
            }
            val metrics = OkapiMicrometerAutoConfiguration().micrometerOutboxMetrics(
                store = store,
                registry = SimpleMeterRegistry(),
                transactionManager = beanCtx.beanFactory.getBeanProvider(PlatformTransactionManager::class.java),
                clock = beanCtx.beanFactory.getBeanProvider(Clock::class.java),
                beanFactory = beanCtx.beanFactory,
                okapiProperties = OkapiProperties(),
            )
            metrics.refresh()
            dsBound shouldBe true
        }

        test(
            "no qualifier + multiple PlatformTransactionManagers (ambiguous): runs without a transaction " +
                "(getIfUnique returns null on ambiguity instead of throwing)",
        ) {
            val ds1: DataSource = h2DataSource()
            val ds2: DataSource = h2DataSource()
            val beanCtx = GenericApplicationContext().apply {
                registerBean<PlatformTransactionManager>("tm1") { DataSourceTransactionManager(ds1) }
                registerBean<PlatformTransactionManager>("tm2") { DataSourceTransactionManager(ds2) }
                refresh()
            }
            var ds1Bound: Boolean? = null
            var ds2Bound: Boolean? = null
            val store = object : OutboxStore by stubStore() {
                override fun countByStatuses(): Map<OutboxStatus, Long> {
                    ds1Bound = TransactionSynchronizationManager.getResource(ds1) != null
                    ds2Bound = TransactionSynchronizationManager.getResource(ds2) != null
                    return emptyMap()
                }
            }
            val metrics = OkapiMicrometerAutoConfiguration().micrometerOutboxMetrics(
                store = store,
                registry = SimpleMeterRegistry(),
                transactionManager = beanCtx.beanFactory.getBeanProvider(PlatformTransactionManager::class.java),
                clock = beanCtx.beanFactory.getBeanProvider(Clock::class.java),
                beanFactory = beanCtx.beanFactory,
                okapiProperties = OkapiProperties(),
            )
            metrics.refresh()
            ds1Bound shouldBe false
            ds2Bound shouldBe false
        }
    }

    context("@ConditionalOnClass(MicrometerOutboxListener) class-level skip path (okapi-micrometer missing from classpath)") {
        // MeterRegistry stays genuinely on the classpath and as a bean here (via SimpleMeterRegistry
        // below and micrometer-core being a real test dependency) -- only MicrometerOutboxListener is
        // hidden, isolating exactly the variable this guard is meant to catch: MeterRegistry present,
        // okapi-micrometer absent. FilteredClassLoader only intercepts loadClass(), so (like the
        // analogous SpringLiquibase guard test) this proves the conditional-skip mechanism, not the
        // exact JVM-native NoClassDefFoundError timing a real missing-dependency classpath would hit --
        // that mechanism is what the class-level @ConditionalOnClass guard exists to trigger before
        // Spring (or the JVM) ever needs to resolve MicrometerOutboxListener as a method return type.
        test("FilteredClassLoader hides MicrometerOutboxListener → context loads, no okapi-micrometer beans registered") {
            ApplicationContextRunner()
                .withClassLoader(FilteredClassLoader(MicrometerOutboxListener::class.java))
                .withConfiguration(AutoConfigurations.of(OkapiMicrometerAutoConfiguration::class.java))
                .withBean(OutboxStore::class.java, { stubStore() })
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()
                    ctx.getBeansOfType(MicrometerOutboxListener::class.java).isEmpty() shouldBe true
                    ctx.getBeansOfType(MicrometerOutboxMetrics::class.java).isEmpty() shouldBe true
                    ctx.getBeansOfType(OutboxMetricsRefresher::class.java).isEmpty() shouldBe true
                }
        }

        test("MicrometerOutboxListener present (normal classpath) → autoconfiguration still fires") {
            // Sanity check the guard's positive path too, so a future typo in the class name
            // string can't silently disable OkapiMicrometerAutoConfiguration on every classpath.
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OkapiMicrometerAutoConfiguration::class.java))
                .withBean(OutboxStore::class.java, { stubStore() })
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()
                    ctx.getBeansOfType(MicrometerOutboxListener::class.java).isEmpty() shouldBe false
                }
        }
    }
})

private fun h2DataSource(): DataSource = JdbcDataSource().apply {
    setURL("jdbc:h2:mem:micrometer-ptm-${System.nanoTime()};DB_CLOSE_DELAY=-1")
    user = "sa"
    password = ""
}
