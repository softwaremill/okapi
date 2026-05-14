package com.softwaremill.okapi.springboot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import liquibase.integration.spring.SpringLiquibase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Instant
import javax.sql.DataSource
import io.kotest.matchers.string.shouldContain as stringShouldContain

/**
 * Verifies bean wiring, property binding, and the structural invariants of okapi's Liquibase
 * auto-configuration (issues #37 + #38 + the related NoClassDefFoundError fix).
 *
 * Slice tests instantiate the inner @Configuration classes directly to avoid `afterPropertiesSet()`
 * (which would try to run Liquibase against a fake DataSource). Real-database coverage lives in
 * [LiquibaseE2ETest]; the structural and reflection-based assertions in this file pin down the
 * architectural decisions that the production code's KDoc explains, so a future "simplification"
 * cannot silently undo them.
 */
class LiquibaseAutoConfigurationTest : FunSpec({

    val dataSource: DataSource = SimpleDriverDataSource()
    val dataSources = mapOf("primary" to dataSource)

    fun postgresConfig(props: OkapiProperties = OkapiProperties()) =
        OkapiLiquibaseAutoConfiguration.PostgresLiquibaseConfiguration(dataSources, dataSource, props)

    fun mysqlConfig(props: OkapiProperties = OkapiProperties()) =
        OkapiLiquibaseAutoConfiguration.MysqlLiquibaseConfiguration(dataSources, dataSource, props)

    // Default contextRunner disables Liquibase to keep slice tests focused on bean wiring rather
    // than actual SpringLiquibase startup against a fake DataSource. Tests that exercise Liquibase
    // explicitly opt back in.
    val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })
        .withBean(DataSource::class.java, { SimpleDriverDataSource() })
        .withOkapiLiquibaseDisabled()

    context("postgres liquibase") {
        test("uses dedicated changelog tables by default") {
            val liquibase = postgresConfig().okapiPostgresLiquibase()

            liquibase.databaseChangeLogTable shouldBe "okapi_databasechangelog"
            liquibase.databaseChangeLogLockTable shouldBe "okapi_databasechangeloglock"
        }

        test("honours custom changelog table names") {
            val props = OkapiProperties(
                liquibase = OkapiProperties.Liquibase(
                    changelogTable = "custom_changelog",
                    changelogLockTable = "custom_changelog_lock",
                ),
            )

            val liquibase = postgresConfig(props).okapiPostgresLiquibase()

            liquibase.databaseChangeLogTable shouldBe "custom_changelog"
            liquibase.databaseChangeLogLockTable shouldBe "custom_changelog_lock"
        }
    }

    context("mysql liquibase") {
        test("uses dedicated changelog tables by default") {
            val liquibase = mysqlConfig().okapiMysqlLiquibase()

            liquibase.databaseChangeLogTable shouldBe "okapi_databasechangelog"
            liquibase.databaseChangeLogLockTable shouldBe "okapi_databasechangeloglock"
        }

        test("honours custom changelog table names") {
            val props = OkapiProperties(
                liquibase = OkapiProperties.Liquibase(
                    changelogTable = "shared_changelog",
                    changelogLockTable = "shared_changelog_lock",
                ),
            )

            val liquibase = mysqlConfig(props).okapiMysqlLiquibase()

            liquibase.databaseChangeLogTable shouldBe "shared_changelog"
            liquibase.databaseChangeLogLockTable shouldBe "shared_changelog_lock"
        }
    }

    context("validation rejects blank table names") {
        data class BlankCase(
            val label: String,
            val build: () -> OkapiProperties.Liquibase,
            val expectedMessage: String,
        )

        val tableMsg = "okapi.liquibase.changelog-table must not be blank."
        val lockMsg = "okapi.liquibase.changelog-lock-table must not be blank."

        listOf(
            BlankCase("changelog-table — empty", { OkapiProperties.Liquibase(changelogTable = "") }, tableMsg),
            BlankCase("changelog-table — whitespace", { OkapiProperties.Liquibase(changelogTable = "   ") }, tableMsg),
            BlankCase("changelog-lock-table — empty", { OkapiProperties.Liquibase(changelogLockTable = "") }, lockMsg),
            BlankCase("changelog-lock-table — whitespace", { OkapiProperties.Liquibase(changelogLockTable = "   ") }, lockMsg),
        ).forEach { case ->
            test(case.label) {
                val ex = shouldThrow<IllegalArgumentException> { case.build() }
                ex.message shouldBe case.expectedMessage
            }
        }
    }

    test("okapi.liquibase.* properties bind to nested config") {
        contextRunner
            .withPropertyValues(
                "okapi.liquibase.changelog-table=app_changelog",
                "okapi.liquibase.changelog-lock-table=app_changelog_lock",
            )
            .run { ctx ->
                val props = ctx.getBean(OkapiProperties::class.java)
                props.liquibase.changelogTable shouldBe "app_changelog"
                props.liquibase.changelogLockTable shouldBe "app_changelog_lock"
            }
    }

    test("blank changelog-table property triggers startup failure") {
        // Pins that init { require(isNotBlank()) } actually propagates through Spring's
        // Binder — without this, a future refactor of OkapiProperties.Liquibase that bypasses
        // the constructor could silently let blank table names through.
        contextRunner
            .withPropertyValues("okapi.liquibase.changelog-table= ")
            .run { ctx ->
                val rootCause = generateSequence(ctx.startupFailure) { it.cause }.last()
                rootCause.message shouldBe "okapi.liquibase.changelog-table must not be blank."
            }
    }

    context("okapi.liquibase.enabled property (issue #38 opt-out)") {
        test("=false → okapi*Liquibase beans are skipped") {
            // contextRunner sets okapi.liquibase.enabled=false by default
            contextRunner.run { ctx ->
                ctx.containsBean("okapiPostgresLiquibase") shouldBe false
                ctx.containsBean("okapiMysqlLiquibase") shouldBe false
            }
        }

        test("=true → LiquibaseDisabledNotice is NOT registered (explicit opt-in path)") {
            // The matchIfMissing=true default path is exercised by all the other Liquibase E2E
            // tests (which omit the property). This test pins that the explicit string "true"
            // is parsed and treated identically by checking the inverse: when enabled=true the
            // LiquibaseDisabledNotice (gated on havingValue="false") must NOT register.
            //
            // FilteredClassLoader(SpringLiquibase) skips both Liquibase config classes so we
            // don't try to run Liquibase against the fake DataSource — the only thing left to
            // evaluate is LiquibaseDisabledNotice's @ConditionalOnProperty.
            ApplicationContextRunner()
                .withClassLoader(FilteredClassLoader(SpringLiquibase::class.java))
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
                .withBean(OutboxStore::class.java, { stubStore() })
                .withBean(MessageDeliverer::class.java, { stubDeliverer() })
                .withBean(DataSource::class.java, { SimpleDriverDataSource() })
                .withPropertyValues("okapi.liquibase.enabled=true")
                .run { ctx ->
                    ctx.getBeansOfType(OkapiLiquibaseAutoConfiguration.LiquibaseDisabledNotice::class.java)
                        .isEmpty() shouldBe true
                }
        }

        test("=false → LiquibaseDisabledNotice IS registered AND logs the actionable WARN") {
            // Symmetric to the test above: when opted out the breadcrumb config must register so
            // its `init {}` block fires the WARN-level startup message. Without this assertion a
            // future cleanup pass that "removes the unused class" or replaces the `init {}` block
            // with a `@PostConstruct` that never runs would silently delete the operability
            // promise the class exists to fulfil.
            //
            // Captures the actual log line so deletion of the warn() body is also caught — not
            // just deletion of the class itself.
            val notice = LoggerFactory.getLogger(
                "com.softwaremill.okapi.springboot.OkapiLiquibaseAutoConfiguration",
            ) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            notice.addAppender(appender)

            try {
                ApplicationContextRunner()
                    .withClassLoader(FilteredClassLoader(SpringLiquibase::class.java))
                    .withConfiguration(
                        AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java),
                    )
                    .withBean(OutboxStore::class.java, { stubStore() })
                    .withBean(MessageDeliverer::class.java, { stubDeliverer() })
                    .withBean(DataSource::class.java, { SimpleDriverDataSource() })
                    .withPropertyValues("okapi.liquibase.enabled=false")
                    .run { ctx ->
                        ctx.getBeansOfType(OkapiLiquibaseAutoConfiguration.LiquibaseDisabledNotice::class.java)
                            .size shouldBe 1
                    }

                val warnEvents = appender.list.filter { it.level == Level.WARN }
                withClue(
                    "LiquibaseDisabledNotice should emit exactly one WARN-level startup log when " +
                        "okapi.liquibase.enabled=false, mentioning the property name and the changelog path " +
                        "so users have a breadcrumb when they later see 'relation okapi_outbox does not exist'.",
                ) {
                    warnEvents.size shouldBe 1
                    val message = warnEvents.single().formattedMessage
                    message stringShouldContain "okapi.liquibase.enabled=false"
                    message stringShouldContain "okapi/db/changelog.xml"
                }
            } finally {
                notice.detachAppender(appender)
            }
        }

        test("=garbage → Spring binder rejects the value at startup") {
            contextRunner
                .withPropertyValues("okapi.liquibase.enabled=garbage")
                .run { ctx ->
                    ctx.startupFailure.shouldNotBeNull()
                }
        }
    }

    context("@ConditionalOnClass(SpringLiquibase) class-level skip path (NCDF guard)") {
        // The real-world failure mode (autocomplete-test on Spring Boot 3.5.x without liquibase-core)
        // is JVM-level: `Class.getDeclaredMethods0()` resolving the SpringLiquibase return type
        // before any Spring condition evaluation. Empirical proof of the fix lives in the
        // okapi-autocomplete-test reproducer — Spring's `FilteredClassLoader` only intercepts
        // `loadClass()`, so it cannot trigger the same JVM-native introspection failure here.
        //
        // What this test exercises is the conditional-skip path: with SpringLiquibase filtered out
        // of `loadClass()` lookups, Spring's class-level `@ConditionalOnClass` evaluates the
        // condition via classname-string lookup and skips the entire configuration class. That
        // is the mechanism the fix relies on; the JVM-introspection avoidance follows once the
        // class is skipped (its methods are never inspected).
        test("FilteredClassLoader hides SpringLiquibase → context loads, Liquibase configs skipped") {
            contextRunner
                .withClassLoader(FilteredClassLoader(SpringLiquibase::class.java))
                .run { ctx ->
                    ctx.startupFailure shouldBe null
                    ctx.containsBean("okapiPostgresLiquibase") shouldBe false
                    ctx.containsBean("okapiMysqlLiquibase") shouldBe false
                }
        }

        // Structural pin: the only @Bean methods declaring a SpringLiquibase return type must live
        // inside @Configuration classes that are themselves gated by @ConditionalOnClass(SpringLiquibase).
        // If a future refactor moves the Liquibase bean back into PostgresStoreConfiguration (or
        // OutboxAutoConfiguration directly), Spring's condition machinery can no longer skip it
        // before `Class.getDeclaredMethods()` introspects the return type — the original NCDF
        // bug returns. FilteredClassLoader cannot detect that regression (it tests the loadClass
        // path, not getDeclaredMethods); this assertion does.
        test("only PostgresLiquibaseConfiguration / MysqlLiquibaseConfiguration declare SpringLiquibase return types") {
            val unguardedClasses = listOf(
                OutboxAutoConfiguration::class.java,
                OutboxAutoConfiguration.PostgresStoreConfiguration::class.java,
                OutboxAutoConfiguration.MysqlStoreConfiguration::class.java,
                OkapiLiquibaseAutoConfiguration::class.java,
            )
            unguardedClasses.forEach { configClass ->
                withClue(
                    "$configClass declares a SpringLiquibase return type — it must be moved to a " +
                        "class with class-level @ConditionalOnClass(SpringLiquibase) or the NCDF " +
                        "bug returns on consumers without liquibase-core",
                ) {
                    configClass.declaredMethods.filter { it.returnType == SpringLiquibase::class.java }
                        .map { it.name }
                        .shouldBeEmpty()
                }
            }
        }

        test("PostgresLiquibaseConfiguration / MysqlLiquibaseConfiguration are gated by @ConditionalOnClass(SpringLiquibase)") {
            listOf(
                OkapiLiquibaseAutoConfiguration.PostgresLiquibaseConfiguration::class.java,
                OkapiLiquibaseAutoConfiguration.MysqlLiquibaseConfiguration::class.java,
            ).forEach { configClass ->
                val annotation = configClass.getAnnotation(ConditionalOnClass::class.java)
                withClue(
                    "$configClass must carry class-level @ConditionalOnClass(SpringLiquibase) " +
                        "so Spring skips it without method introspection",
                ) {
                    annotation.shouldNotBeNull()
                    annotation.value.toList() shouldContain SpringLiquibase::class
                }
            }
        }

        // Reflection-based meta-test, modeled on PR #41 (okapi-spring-boot Micrometer fix).
        // `@AutoConfigureAfter(name = ...)` silently drops entries whose class is missing — if NONE
        // resolve, the ordering hint is a no-op. This is the failure mode of the Micrometer
        // regression (#36): a Spring Boot 4.0-only path was declared while the project also runs
        // on 3.5.x.
        //
        // Two-layer assertion:
        //   1. Unconditional structural check: annotation is present and non-empty.
        //   2. Runtime classpath check: at least one declared name resolves IF Spring Boot's
        //      Liquibase autoconfig is on the classpath at all (3.5.x ships it in
        //      spring-boot-autoconfigure; 4.0.x does not).
        test("@AutoConfigureAfter on OkapiLiquibaseAutoConfiguration is structurally sound and resolvable when applicable") {
            // Use AnnotatedElementUtils so the @AutoConfigureAfter meta-annotation declared on
            // @AutoConfiguration (and aliased via @AliasFor) is also picked up — JDK's plain
            // getAnnotation(...) only looks at direct annotations.
            val annotation = AnnotatedElementUtils.findMergedAnnotation(
                OkapiLiquibaseAutoConfiguration::class.java,
                AutoConfigureAfter::class.java,
            )
            annotation.shouldNotBeNull()

            val declaredNames = annotation.name.toList()
            withClue("@AutoConfigureAfter must declare at least one target name; an empty list silently no-ops the ordering contract") {
                declaredNames.shouldNotBeEmpty()
            }

            val classLoader = OkapiLiquibaseAutoConfiguration::class.java.classLoader
            val knownSpringBootLiquibasePaths = listOf(
                "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
                "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration",
            )
            val resolvableSpringBootPaths = knownSpringBootLiquibasePaths.filter { canLoadClass(it, classLoader) }
            if (resolvableSpringBootPaths.isEmpty()) {
                // No Spring Boot Liquibase autoconfig on this runtime — ordering is a no-op and
                // there is nothing to shadow. Structural check above already pinned the annotation
                // shape; nothing further to assert.
                return@test
            }

            val resolvableDeclaredNames = declaredNames.filter { canLoadClass(it, classLoader) }
            withClue(
                "@AutoConfigureAfter declares $declaredNames but none resolve on this Spring Boot runtime " +
                    "($resolvableSpringBootPaths is on the classpath); the ordering hint is silently ignored " +
                    "and OkapiLiquibaseAutoConfiguration may run before Spring Boot's LiquibaseAutoConfiguration, " +
                    "shadowing the host application's liquibase bean.",
            ) {
                resolvableDeclaredNames.shouldNotBeEmpty()
            }
        }
    }

    context("@ConditionalOnMissingBean is name-based (design pin for issue #38 coexistence)") {
        // PostgresLiquibaseConfiguration's KDoc explains: name-based, NOT type-based. A future
        // "simplification" to type-based @ConditionalOnMissingBean(SpringLiquibase) silently
        // shadows the host application's own SpringLiquibase bean (Mode 1 of issue #38).
        // These tests pin the architectural decision against that single-token regression.

        test("okapiPostgresLiquibase factory uses @ConditionalOnMissingBean(name = ...)") {
            val method = OkapiLiquibaseAutoConfiguration.PostgresLiquibaseConfiguration::class.java
                .getDeclaredMethod("okapiPostgresLiquibase")
            val cond = method.getAnnotation(ConditionalOnMissingBean::class.java)
            cond.shouldNotBeNull()
            cond.name.toList() shouldContain "okapiPostgresLiquibase"
            withClue(
                "type-based @ConditionalOnMissingBean(SpringLiquibase) would skip okapi's bean " +
                    "whenever the host app provides its own — re-introducing the issue #38 Mode 1 " +
                    "shadowing bug",
            ) {
                cond.value.toList().shouldBeEmpty()
            }
        }

        test("okapiMysqlLiquibase factory uses @ConditionalOnMissingBean(name = ...)") {
            val method = OkapiLiquibaseAutoConfiguration.MysqlLiquibaseConfiguration::class.java
                .getDeclaredMethod("okapiMysqlLiquibase")
            val cond = method.getAnnotation(ConditionalOnMissingBean::class.java)
            cond.shouldNotBeNull()
            cond.name.toList() shouldContain "okapiMysqlLiquibase"
            cond.value.toList().shouldBeEmpty()
        }

        test("dual-module classpath: only ONE okapi*Liquibase bean activates — matching OutboxStore winner") {
            // Pins the OutboxStore-precedence contract for Liquibase auto-config (issue #38
            // / KOJAK-80). Both `okapi-postgres` and `okapi-mysql` are on the test classpath
            // (build.gradle.kts:35-36). The `*OutboxStore` factories share
            // `@ConditionalOnMissingBean(OutboxStore::class)`, so exactly ONE store bean wins.
            // The Liquibase configs MUST mirror that precedence: registering both
            // `okapiPostgresLiquibase` and `okapiMysqlLiquibase` against the same DataSource
            // would let the second-evaluated Liquibase apply wrong-engine DDL at startup and
            // fail (duplicate index, wrong-engine syntax, or shared tracking-table collisions).
            //
            // The production fix lives in [OkapiLiquibaseAutoConfiguration] — a separate
            // `@AutoConfiguration(after = OutboxAutoConfiguration)` so that the per-engine
            // `@ConditionalOnBean(<X>OutboxStore)` gates fire AFTER the store factories have
            // registered their winning bean. Within a single auto-config those gates would
            // evaluate before sibling beans are visible and would always skip.
            //
            // SuppressSpringLiquibaseRun prevents afterPropertiesSet() from trying to migrate
            // a fake DataSource — we're asserting bean activation, not migration behaviour.
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
                .withBean(MessageDeliverer::class.java, { stubDeliverer() })
                .withBean(DataSource::class.java, { SimpleDriverDataSource() })
                .withInitializer { ctx ->
                    ctx.beanFactory.addBeanPostProcessor(SuppressSpringLiquibaseRun())
                }
                .run { ctx ->
                    ctx.startupFailure shouldBe null

                    val storeBean = ctx.getBean(OutboxStore::class.java)
                    val expected = when (storeBean) {
                        is com.softwaremill.okapi.postgres.PostgresOutboxStore -> "okapiPostgresLiquibase"
                        is com.softwaremill.okapi.mysql.MysqlOutboxStore -> "okapiMysqlLiquibase"
                        else -> error("unexpected OutboxStore type ${storeBean::class}")
                    }
                    val active = listOf("okapiPostgresLiquibase", "okapiMysqlLiquibase")
                        .filter { ctx.containsBean(it) }

                    // Diagnostic: when the assertion fails, surface what each registered
                    // SpringLiquibase bean would have done (changelog path + dataSource
                    // identity) so the reader sees concretely why dual activation is broken.
                    val diagnostic = active.joinToString("\n") { name ->
                        val bean = ctx.getBean(name, SpringLiquibase::class.java)
                        "  $name → changelog=${bean.changeLog}, dataSource=${System.identityHashCode(bean.dataSource)}"
                    }
                    withClue(
                        "Active OutboxStore is ${storeBean::class.simpleName}; expected exactly the " +
                            "matching Liquibase bean ($expected) to activate, but found: $active\n$diagnostic",
                    ) {
                        active shouldBe listOf(expected)
                    }
                }
        }

        test("user @Bean(\"okapiPostgresLiquibase\") replaces okapi's default bean") {
            // Pins that the documented override mechanism actually works: a host app supplying
            // its own bean by the well-known name takes precedence over okapi's auto-configured
            // factory (because @ConditionalOnMissingBean(name = "okapiPostgresLiquibase") sees
            // the user's definition and skips okapi's). Switching to type-based
            // @ConditionalOnMissingBean(SpringLiquibase) silently breaks this — the user's bean
            // still registers, but okapi's also registers, producing two SpringLiquibase beans.
            //
            // setShouldRun(false) prevents the user's bean from actually running Liquibase
            // against the fake DataSource. MysqlOutboxStore is hidden via FilteredClassLoader
            // so the MySQL Liquibase config class is also skipped (otherwise it would try to
            // run okapi's MySQL changelog against the fake DataSource).
            val userBean = SpringLiquibase().apply { setShouldRun(false) }
            ApplicationContextRunner()
                .withClassLoader(FilteredClassLoader(com.softwaremill.okapi.mysql.MysqlOutboxStore::class.java))
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
                .withBean(OutboxStore::class.java, { stubStore() })
                .withBean(MessageDeliverer::class.java, { stubDeliverer() })
                .withBean(DataSource::class.java, { SimpleDriverDataSource() })
                .withBean("okapiPostgresLiquibase", SpringLiquibase::class.java, { userBean })
                // okapi.liquibase.enabled defaults to true (no opt-out): the override decision
                // must come from @ConditionalOnMissingBean, not from the property.
                .run { ctx ->
                    ctx.getBean("okapiPostgresLiquibase", SpringLiquibase::class.java) shouldBeSameInstanceAs userBean
                    // Exactly one SpringLiquibase bean — the user's. okapi's factory must have been
                    // skipped by @ConditionalOnMissingBean(name = "okapiPostgresLiquibase").
                    ctx.getBeansOfType(SpringLiquibase::class.java).size shouldBe 1
                }
        }
    }
})

private fun ApplicationContextRunner.withOkapiLiquibaseDisabled(): ApplicationContextRunner =
    withPropertyValues("okapi.liquibase.enabled=false")

/**
 * Disables [SpringLiquibase.afterPropertiesSet] migration runs by flipping `shouldRun=false`
 * before init methods fire. Lets dual-activation tests inspect registered beans without
 * actually trying to apply a changelog against a fake DataSource.
 */
private class SuppressSpringLiquibaseRun : BeanPostProcessor {
    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (bean is SpringLiquibase) bean.setShouldRun(false)
        return bean
    }
}

private fun canLoadClass(fqcn: String, classLoader: ClassLoader): Boolean = try {
    Class.forName(fqcn, false, classLoader)
    true
} catch (_: ClassNotFoundException) {
    false
}

private fun stubStore() = object : OutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}

private fun stubDeliverer() = object : MessageDeliverer {
    override val type = "stub"
    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
}
