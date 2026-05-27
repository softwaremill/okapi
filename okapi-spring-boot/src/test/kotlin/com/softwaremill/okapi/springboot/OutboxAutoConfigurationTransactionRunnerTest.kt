package com.softwaremill.okapi.springboot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.h2.jdbcx.JdbcDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.support.GenericApplicationContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.ResourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

class OutboxAutoConfigurationTransactionRunnerTest : FunSpec({

    val baseRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })

    test("derives SpringTransactionRunner from PlatformTransactionManager when present") {
        val ds: DataSource = SimpleDriverDataSource()
        baseRunner
            .withBean(DataSource::class.java, { ds })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .run { ctx ->
                ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
            }
    }

    test("auto-built TransactionTemplate is NOT read-only (defends against globally read-only TX defaults)") {
        val ds: DataSource = h2DataSource()
        baseRunner
            .withBean(DataSource::class.java, { ds })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .run { ctx ->
                val runner = ctx.getBean(TransactionRunner::class.java)
                runner.runInTransaction { TransactionSynchronizationManager.isCurrentTransactionReadOnly() } shouldBe false
            }
    }

    test("okapi.transaction-manager-qualifier YAML key binds to OkapiProperties.transactionManagerQualifier (kebab → camelCase)") {
        // Pins the property name — a field rename silently breaks user config without this gate.
        baseRunner
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withBean(TransactionRunner::class.java, {
                object : TransactionRunner {
                    override fun <T> runInTransaction(block: () -> T): T = block()
                }
            })
            .withPropertyValues("okapi.transaction-manager-qualifier=outboxTm")
            .run { ctx ->
                ctx.getBean(OkapiProperties::class.java).transactionManagerQualifier shouldBe "outboxTm"
            }
    }

    test("blank okapi.transaction-manager-qualifier triggers startup failure with require() message") {
        // Pins that the require() in OkapiProperties.init propagates through Spring's Binder —
        // moving it to a getter would silently let blank qualifiers through.
        baseRunner
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withBean(TransactionRunner::class.java, {
                object : TransactionRunner {
                    override fun <T> runInTransaction(block: () -> T): T = block()
                }
            })
            .withPropertyValues("okapi.transaction-manager-qualifier= ")
            .run { ctx ->
                val rootCause = generateSequence(ctx.startupFailure) { it.cause }.last()
                rootCause.message.shouldNotBeNull()
                    .shouldContain("okapi.transaction-manager-qualifier must not be blank")
            }
    }

    test("publish-only deployment: both schedulers disabled + no PTM + no user TransactionRunner — context starts cleanly") {
        baseRunner
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withPropertyValues(
                "okapi.processor.enabled=false",
                "okapi.purger.enabled=false",
            )
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                ctx.containsBean("okapiTransactionRunner") shouldBe false
                ctx.containsBean("outboxProcessorScheduler") shouldBe false
                ctx.containsBean("outboxPurgerScheduler") shouldBe false
                ctx.getBean(SpringOutboxPublisher::class.java).shouldNotBeNull()
            }
    }

    test("fails context refresh with actionable message when no PTM and no user TransactionRunner") {
        baseRunner
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .run { ctx ->
                val failure = ctx.startupFailure
                failure.shouldNotBeNull()
                val rootCause = generateSequence(failure as Throwable?) { it.cause }.last()
                rootCause.shouldBeInstanceOf<NoSuchBeanDefinitionException>()
                rootCause.message.shouldNotBeNull().also {
                    it.shouldContain("okapi-spring-boot requires a TransactionRunner bean")
                    it.shouldContain("PlatformTransactionManager")
                    it.shouldContain("@Bean TransactionRunner")
                }
            }
    }

    test("PROBE: can a TransactionTemplate bean even exist with a null transactionManager?") {
        // The okapiTransactionRunner factory has `anyTemplate?.transactionManager ?: resolve(...)`.
        // pr-test-analyzer flagged the null branch as untested. But TransactionTemplate implements
        // InitializingBean and afterPropertiesSet() throws "Property 'transactionManager' is required"
        // when null — Spring invokes that on every @Bean. This probe empirically establishes whether
        // the null-TM branch is reachable for a Spring-managed TT at all, or is defensive dead code.
        val ds: DataSource = SimpleDriverDataSource()
        baseRunner
            .withBean(DataSource::class.java, { ds })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .withBean(
                "nullTmTemplate",
                org.springframework.transaction.support.TransactionTemplate::class.java,
                { org.springframework.transaction.support.TransactionTemplate() },
            )
            .run { ctx ->
                // Record the empirically observed behaviour; the assertion below documents it.
                val startupFailed = ctx.startupFailure != null
                val rootMsg = ctx.startupFailure?.let { f ->
                    generateSequence(f as Throwable?) { it.cause }.last().message
                }
                withClue("observed startupFailed=$startupFailed rootMsg=$rootMsg") {
                    // Spring rejects a TransactionTemplate bean with null transactionManager at
                    // InitializingBean.afterPropertiesSet(): the factory's `?:` fallback is therefore
                    // unreachable for a *Spring-managed* TT. It remains a correct defensive guard for
                    // the theoretical non-Spring path, but cannot be exercised via the bean container.
                    startupFailed shouldBe true
                    rootMsg.shouldNotBeNull().shouldContain("transactionManager")
                }
            }
    }

    test("user-provided @Bean TransactionTemplate is honoured (autoconfig does not silently shadow it)") {
        // A "silent shadow" regression would discard the user's TX settings (timeout/propagation/isolation)
        // for scheduler ticks while @Transactional code paths use the correctly configured template.
        val ds: DataSource = SimpleDriverDataSource()
        baseRunner
            .withBean(DataSource::class.java, { ds })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .withUserConfiguration(CustomTransactionTemplateConfiguration::class.java)
            .run { ctx ->
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                runner.transactionTemplate.shouldBeSameInstanceAs(CustomTransactionTemplateConfiguration.TEMPLATE)
            }
    }

    test("user-provided TransactionRunner bean is honoured without PTM (@ConditionalOnMissingBean)") {
        baseRunner
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withUserConfiguration(CustomRunnerConfiguration::class.java)
            .run { ctx ->
                ctx.getBean(TransactionRunner::class.java).shouldBeSameInstanceAs(CustomRunnerConfiguration.RUNNER)
            }
    }

    test("fails with distinct message when multiple PTMs are present and none is @Primary") {
        val ds: DataSource = SimpleDriverDataSource()
        baseRunner
            .withBean(DataSource::class.java, { ds })
            .withUserConfiguration(TwoPtmsNoPrimaryUserConfig::class.java)
            .run { ctx ->
                val failure = ctx.startupFailure
                failure.shouldNotBeNull()
                val rootCause = generateSequence(failure as Throwable?) { it.cause }.last()
                rootCause.shouldBeInstanceOf<NoSuchBeanDefinitionException>()
                rootCause.message.shouldNotBeNull().also {
                    it.shouldContain("Multiple PlatformTransactionManager beans found")
                    it.shouldContain("@Primary")
                    it.shouldContain("okapi.transaction-manager-qualifier")
                }
            }
    }

    test("uses @Primary PTM when multiple are present") {
        val ds: DataSource = SimpleDriverDataSource()
        baseRunner
            .withBean(DataSource::class.java, { ds })
            .withUserConfiguration(PrimaryDstAndDummyPtmConfig::class.java)
            .run { ctx ->
                ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
            }
    }

    test("okapi.transaction-manager-qualifier picks the named PTM (overrides @Primary), proven via DS-match validation") {
        // Setup designed so a buggy "ignore qualifier" implementation would FAIL:
        //   - appDs (primary), outboxDs (secondary) DataSources
        //   - appTm = DST(appDs), @Primary
        //   - outboxTm = DST(outboxDs)
        //   - okapi.datasource-qualifier=outboxDs (so resolveDataSource() == outboxDs)
        //   - okapi.transaction-manager-qualifier=outboxTm
        // Correct: qualifier picks outboxTm → validation: outboxTm.dataSource == outboxDs → ok.
        // Buggy (qualifier ignored): @Primary appTm picked → validation: appDs ≠ outboxDs → fail-fast.
        val appDs: DataSource = SimpleDriverDataSource()
        val outboxDs: DataSource = SimpleDriverDataSource()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withInitializer { context ->
                val gac = context as GenericApplicationContext
                gac.registerBean<DataSource>("appDs", primary = true) { appDs }
                gac.registerBean<DataSource>("outboxDs") { outboxDs }
                gac.registerBean<PlatformTransactionManager>("appTm", primary = true) { DataSourceTransactionManager(appDs) }
                gac.registerBean<PlatformTransactionManager>("outboxTm") { DataSourceTransactionManager(outboxDs) }
            }
            .withPropertyValues(
                "okapi.datasource-qualifier=outboxDs",
                "okapi.transaction-manager-qualifier=outboxTm",
            )
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
            }
    }

    test("okapi.transaction-manager-qualifier wins over Spring Boot's auto-registered TT around @Primary PTM") {
        // Regression guard for the bug observed by ramafasa on PR #49: when Spring Boot's
        // TransactionAutoConfiguration registers a TransactionTemplate for the @Primary PTM,
        // `transactionTemplate.getIfUnique()` returns that TT, `anyTemplate.transactionManager`
        // short-circuits to @Primary, and `resolvePlatformTransactionManager` (the only call site
        // that reads the qualifier) is never invoked — so the qualifier was silently ignored in
        // every typical Spring Boot deployment.
        //
        // Setup is identical to the no-Boot-TT case above, but ALSO loads TransactionAutoConfiguration.
        // Detection mechanism is the multi-DS validation, which fails fast iff the wrong PTM is picked:
        //   - qualifier honoured → outboxTm (DST(outboxDs)) → outboxDs == resolveDataSource() → OK
        //   - qualifier ignored  → appTm (DST(appDs))      → appDs   != outboxDs               → FAIL
        val txAutoConfigClass: Class<*> = listOf(
            "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
            "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration",
        ).firstNotNullOfOrNull { fqcn ->
            try {
                Class.forName(fqcn)
            } catch (_: ClassNotFoundException) {
                null
            }
        } ?: error("TransactionAutoConfiguration not on test classpath.")

        val appDs: DataSource = SimpleDriverDataSource()
        val outboxDs: DataSource = SimpleDriverDataSource()
        val appTm = DataSourceTransactionManager(appDs)
        val outboxTm = DataSourceTransactionManager(outboxDs)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, txAutoConfigClass))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withInitializer { context ->
                val gac = context as GenericApplicationContext
                gac.registerBean<DataSource>("appDs", primary = true) { appDs }
                gac.registerBean<DataSource>("outboxDs") { outboxDs }
                gac.registerBean<PlatformTransactionManager>("appTm", primary = true) { appTm }
                gac.registerBean<PlatformTransactionManager>("outboxTm") { outboxTm }
            }
            .withPropertyValues(
                "okapi.datasource-qualifier=outboxDs",
                "okapi.transaction-manager-qualifier=outboxTm",
            )
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                // Strong assertion: the runner's TT must wrap the qualified PTM by reference identity,
                // not Boot's auto-TT (which wraps @Primary appTm).
                runner.transactionTemplate.transactionManager shouldBeSameInstanceAs outboxTm
            }
    }

    test("qualifier matching Boot's auto-TT's PTM reuses that TT verbatim (preserves its TX settings)") {
        // Edge of the qualifier-precedence rule: when the qualifier happens to name the same PTM
        // that Boot's auto-TT already wraps, the factory must NOT build a fresh TransactionTemplate
        // around it — that would silently discard timeout/isolation/propagation Boot configured.
        // The `anyTemplate.transactionManager === ptm` short-circuit covers this.
        val txAutoConfigClass: Class<*> = listOf(
            "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
            "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration",
        ).firstNotNullOfOrNull {
            try {
                Class.forName(it)
            } catch (_: ClassNotFoundException) {
                null
            }
        } ?: error("TransactionAutoConfiguration not on test classpath.")

        val ds: DataSource = SimpleDriverDataSource()
        val onlyPtm = DataSourceTransactionManager(ds)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, txAutoConfigClass))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withBean(DataSource::class.java, { ds })
            .withBean("onlyPtm", PlatformTransactionManager::class.java, { onlyPtm })
            .withPropertyValues("okapi.transaction-manager-qualifier=onlyPtm")
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                val bootTt = ctx.getBean(org.springframework.transaction.support.TransactionTemplate::class.java)
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                // The runner's TT must be the SAME OBJECT as Boot's auto-TT — proves the factory
                // didn't rebuild a fresh TT just because qualifier was set.
                runner.transactionTemplate shouldBeSameInstanceAs bootTt
            }
    }

    test("qualifier wins over a user-supplied @Bean TransactionTemplate that wraps a different PTM") {
        // Conflicting config: user defined both their own TransactionTemplate AND set
        // okapi.transaction-manager-qualifier pointing at a different PTM. Resolution rule:
        // explicit qualifier > implicit TT. The user TT's custom timeout/isolation/propagation is
        // discarded silently — this is a deliberate trade-off so the explicit qualifier semantics
        // are not silently undermined by a stray @Bean TransactionTemplate.
        val appDs: DataSource = SimpleDriverDataSource()
        val outboxDs: DataSource = SimpleDriverDataSource()
        val appTm = DataSourceTransactionManager(appDs)
        val outboxTm = DataSourceTransactionManager(outboxDs)
        // User's own TT wraps appTm with a distinctive non-default timeout — detectable below.
        val userTt = org.springframework.transaction.support.TransactionTemplate(appTm).apply { timeout = 42 }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withInitializer { context ->
                val gac = context as GenericApplicationContext
                gac.registerBean<DataSource>("appDs", primary = true) { appDs }
                gac.registerBean<DataSource>("outboxDs") { outboxDs }
                gac.registerBean<PlatformTransactionManager>("appTm", primary = true) { appTm }
                gac.registerBean<PlatformTransactionManager>("outboxTm") { outboxTm }
                gac.registerBean<org.springframework.transaction.support.TransactionTemplate>("userTt") { userTt }
            }
            .withPropertyValues(
                "okapi.datasource-qualifier=outboxDs",
                "okapi.transaction-manager-qualifier=outboxTm",
            )
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                // Qualifier wins: runner's TT wraps outboxTm, NOT user's appTm-wrapping TT.
                runner.transactionTemplate.transactionManager shouldBeSameInstanceAs outboxTm
                // Fresh TT built (not userTt), so userTt's timeout=42 is NOT inherited.
                runner.transactionTemplate shouldNotBeSameInstanceAs userTt
            }
    }

    test("ResourceTransactionManager PTM with non-DataSource resourceFactory: WARN includes actual resourceFactory class name") {
        // Pins WARN content: must mention the actual resourceFactory class, not just the static
        // JpaTransactionManager/Hibernate examples — so non-JPA users see the real resource type.
        val ds: DataSource = SimpleDriverDataSource()
        val customRtm = JpaLikeRtmTransactionManager(resourceFactory = "myDistinctiveResourceFactory")
        val targetLogger = LoggerFactory.getLogger(OutboxAutoConfiguration::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        targetLogger.addAppender(appender)
        try {
            baseRunner
                .withBean("outboxDs", DataSource::class.java, { ds })
                .withBean(PlatformTransactionManager::class.java, { customRtm })
                .withPropertyValues("okapi.datasource-qualifier=outboxDs")
                .run { ctx -> ctx.startupFailure.shouldBeNull() }
            val warnText = appender.list.filter { it.level == Level.WARN }.joinToString("\n") { it.formattedMessage }
            warnText.shouldContain("java.lang.String")
        } finally {
            targetLogger.detachAppender(appender)
        }
    }

    test("non-ResourceTransactionManager PTM without okapi.datasource-qualifier (single-DS assumption): INFO breadcrumb emitted") {
        // INFO breadcrumb gives operators something to grep for after a multi-DS migration that
        // forgot to set okapi.datasource-qualifier — otherwise the misconfiguration is silent.
        val ds: DataSource = SimpleDriverDataSource()
        val targetLogger = LoggerFactory.getLogger(OutboxAutoConfiguration::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        targetLogger.addAppender(appender)
        try {
            baseRunner
                .withBean(DataSource::class.java, { ds })
                .withBean(PlatformTransactionManager::class.java, { DummyTransactionManager() })
                // no okapi.datasource-qualifier
                .run { ctx -> ctx.startupFailure.shouldBeNull() }
            val infos = appender.list.filter { it.level == Level.INFO }
            val combined = infos.joinToString("\n") { it.formattedMessage }
            combined.shouldContain("does not implement ResourceTransactionManager")
            combined.shouldContain("okapi.transaction-manager-qualifier")
        } finally {
            targetLogger.detachAppender(appender)
        }
    }

    test("non-ResourceTransactionManager PTM with okapi.datasource-qualifier set: context starts AND emits actionable WARN") {
        // Asserts WARN content (not just presence) so the operator-facing message cannot silently
        // rot — e.g. deleting the warn() body would still let the context start, breaking no other test.
        val ds: DataSource = SimpleDriverDataSource()
        val targetLogger = LoggerFactory.getLogger(OutboxAutoConfiguration::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        targetLogger.addAppender(appender)
        try {
            baseRunner
                .withBean("outboxDs", DataSource::class.java, { ds })
                .withBean(PlatformTransactionManager::class.java, { DummyTransactionManager() })
                .withPropertyValues("okapi.datasource-qualifier=outboxDs")
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()
                    ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                }
            val warnings = appender.list.filter { it.level == Level.WARN }
            warnings.shouldNotBeEmpty()
            val combined = warnings.joinToString("\n") { it.formattedMessage }
            combined.shouldContain("okapi.datasource-qualifier")
            combined.shouldContain("does not implement ResourceTransactionManager")
            combined.shouldContain("okapi.transaction-manager-qualifier")
        } finally {
            targetLogger.detachAppender(appender)
        }
    }

    test("okapi.transaction-manager-qualifier pointing to a nonexistent bean fails with actionable message") {
        val ds: DataSource = SimpleDriverDataSource()
        baseRunner
            .withBean(DataSource::class.java, { ds })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .withPropertyValues("okapi.transaction-manager-qualifier=missingTm")
            .run { ctx ->
                val failure = ctx.startupFailure
                failure.shouldNotBeNull()
                val allMessages = generateSequence(failure as Throwable?) { it.cause }.mapNotNull { it.message }.toList()
                allMessages.any { it.contains("okapi.transaction-manager-qualifier") } shouldBe true
                allMessages.any { it.contains("missingTm") } shouldBe true
            }
    }

    test("okapi.transaction-manager-qualifier pointing to a bean of wrong type (e.g. DataSource) fails with actionable message") {
        // Common typo: user means `okapi.transaction-manager-qualifier=outboxTm` but writes
        // `=outboxDs` (the DataSource bean name). Spring throws `BeanNotOfRequiredTypeException`,
        // NOT `NoSuchBeanDefinitionException`, so a naive `catch (NoSuchBeanDefinitionException)`
        // never wraps the error and the user sees a cryptic message without okapi context.
        val outboxDs: DataSource = SimpleDriverDataSource()
        baseRunner
            .withBean("outboxDs", DataSource::class.java, { outboxDs })
            .withBean("outboxTm", PlatformTransactionManager::class.java, { DataSourceTransactionManager(outboxDs) })
            // typo: pointing to the DataSource bean instead of the PTM bean
            .withPropertyValues("okapi.transaction-manager-qualifier=outboxDs")
            .run { ctx ->
                val failure = ctx.startupFailure
                failure.shouldNotBeNull()
                val allMessages = generateSequence(failure as Throwable?) { it.cause }.mapNotNull { it.message }.toList()
                allMessages.any { it.contains("okapi.transaction-manager-qualifier") } shouldBe true
                allMessages.any { it.contains("outboxDs") } shouldBe true
                allMessages.any {
                    it.contains("not a PlatformTransactionManager") || it.contains("PlatformTransactionManager")
                } shouldBe true
            }
    }

    test("PTM bound to a different DataSource than outbox fails-fast with PTM↔DS mismatch message") {
        val appDs: DataSource = SimpleDriverDataSource()
        val outboxDs: DataSource = SimpleDriverDataSource()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withInitializer { context ->
                val gac = context as GenericApplicationContext
                gac.registerBean<DataSource>("appDs", primary = true) { appDs }
                gac.registerBean<DataSource>("outboxDs") { outboxDs }
                gac.registerBean<PlatformTransactionManager>("appTm") { DataSourceTransactionManager(appDs) }
            }
            .withPropertyValues("okapi.datasource-qualifier=outboxDs")
            .run { ctx ->
                val failure = ctx.startupFailure
                failure.shouldNotBeNull()
                val rootCause = generateSequence(failure as Throwable?) { it.cause }.last()
                rootCause.message.shouldNotBeNull().also {
                    it.shouldContain("bound to a different DataSource")
                    it.shouldContain("FOR UPDATE SKIP LOCKED")
                    it.shouldContain("okapi.transaction-manager-qualifier")
                }
            }
    }

    // -----------------------------------------------------------------------------------------
    // C1 regression guard: ResourceTransactionManager with non-DataSource resourceFactory.
    // JpaTransactionManager and HibernateTransactionManager both implement ResourceTransactionManager
    // but their resourceFactory is EntityManagerFactory or SessionFactory respectively — NOT a
    // DataSource. (JtaTransactionManager doesn't implement RTM at all and falls through the same
    // WARN branch as Exposed's SpringTransactionManager.) Earlier shape of validatePtmDataSourceMatch
    // did `as? DataSource ?: return`, silently early-returning and bypassing the WARN. This test
    // pins that the cast failure now falls through to the WARN branch instead.
    // -----------------------------------------------------------------------------------------
    test(
        "BUG C1: RTM with non-DataSource resourceFactory + okapi.datasource-qualifier set: WARN should be logged but currently is silent",
    ) {
        val ds: DataSource = SimpleDriverDataSource()
        val jpaLikeTm = JpaLikeRtmTransactionManager(resourceFactory = Any())

        val targetLogger = LoggerFactory.getLogger(OutboxAutoConfiguration::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        targetLogger.addAppender(appender)
        try {
            baseRunner
                .withBean("outboxDs", DataSource::class.java, { ds })
                .withBean(PlatformTransactionManager::class.java, { jpaLikeTm })
                .withPropertyValues("okapi.datasource-qualifier=outboxDs")
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()
                    ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                }
            val warningsAboutValidation = appender.list.filter {
                it.level == Level.WARN && it.formattedMessage.contains("okapi.datasource-qualifier")
            }
            warningsAboutValidation.shouldNotBeEmpty()
        } finally {
            targetLogger.detachAppender(appender)
        }
    }

    // -----------------------------------------------------------------------------------------
    // C2 demonstration: TransactionAwareDataSourceProxy false-positive.
    // Spring's recommended pattern for outbox-style scenarios is to register
    // TransactionAwareDataSourceProxy(rawDs) as the application-facing bean for query helpers, while
    // the PlatformTransactionManager is wired with the RAW DataSource (Spring docs explicitly say:
    // "TransactionAwareDataSourceProxy should NOT be passed to a PTM"). With this correct pattern:
    //   - ptm.resourceFactory === rawDs
    //   - resolveDataSource() === proxyDs (the bean qualified by okapi.datasource-qualifier)
    // Our validation uses reference equality (`!==`) → fail-fast on a *correctly* configured app.
    // -----------------------------------------------------------------------------------------
    test("BUG C2: TransactionAwareDataSourceProxy(rawDs) on outbox bean + PTM(rawDs) is Spring's recommended pattern, must not fail") {
        val rawDs: DataSource = SimpleDriverDataSource()
        val proxyDs: DataSource = TransactionAwareDataSourceProxy(rawDs)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withInitializer { context ->
                val gac = context as GenericApplicationContext
                gac.registerBean<DataSource>("outboxDs", primary = true) { proxyDs }
                gac.registerBean<PlatformTransactionManager>("outboxTm") { DataSourceTransactionManager(rawDs) }
            }
            .withPropertyValues("okapi.datasource-qualifier=outboxDs")
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
            }
    }

    // Unit tests for the unwrapDataSource helper: nested chains, null targets, and cycles.
    context("unwrapDataSource") {
        test("returns Resolved with the input itself when not a DelegatingDataSource") {
            val raw: DataSource = SimpleDriverDataSource()
            val result = OutboxAutoConfiguration.unwrapDataSource(raw)
            result.shouldBeInstanceOf<OutboxAutoConfiguration.Companion.Unwrapped.Resolved>()
            result.ds shouldBeSameInstanceAs raw
        }

        test("unwraps a single-level TransactionAwareDataSourceProxy to Resolved(raw)") {
            val raw: DataSource = SimpleDriverDataSource()
            val proxy: DataSource = TransactionAwareDataSourceProxy(raw)
            val result = OutboxAutoConfiguration.unwrapDataSource(proxy)
            result.shouldBeInstanceOf<OutboxAutoConfiguration.Companion.Unwrapped.Resolved>()
            result.ds shouldBeSameInstanceAs raw
        }

        test("unwraps a nested chain TADP -> LCDP -> raw down to Resolved(raw)") {
            val raw: DataSource = SimpleDriverDataSource()
            val nested: DataSource = TransactionAwareDataSourceProxy(LazyConnectionDataSourceProxy(raw))
            val result = OutboxAutoConfiguration.unwrapDataSource(nested)
            result.shouldBeInstanceOf<OutboxAutoConfiguration.Companion.Unwrapped.Resolved>()
            result.ds shouldBeSameInstanceAs raw
        }

        test("returns Unresolvable(NULL_TARGET) when a DelegatingDataSource has no targetDataSource") {
            // LazyConnectionDataSourceProxy ships with a no-arg constructor that leaves targetDataSource null
            // until setTargetDataSource is called. The helper must surface this as an explicit Unresolvable
            // outcome so callers do not mistake a not-yet-wired proxy for a mismatched DataSource.
            val proxy: DataSource = LazyConnectionDataSourceProxy()
            val result = OutboxAutoConfiguration.unwrapDataSource(proxy)
            result.shouldBeInstanceOf<OutboxAutoConfiguration.Companion.Unwrapped.Unresolvable>()
            result.stoppedAt shouldBeSameInstanceAs proxy
            result.reason shouldBe OutboxAutoConfiguration.Companion.Unwrapped.Reason.NULL_TARGET
        }

        test("returns Unresolvable(CYCLE) on a self-referencing DelegatingDataSource").config(timeout = 2.seconds) {
            val cyclic = LazyConnectionDataSourceProxy()
            cyclic.setTargetDataSource(cyclic)
            val result = OutboxAutoConfiguration.unwrapDataSource(cyclic)
            result.shouldBeInstanceOf<OutboxAutoConfiguration.Companion.Unwrapped.Unresolvable>()
            result.stoppedAt shouldBeSameInstanceAs cyclic
            result.reason shouldBe OutboxAutoConfiguration.Companion.Unwrapped.Reason.CYCLE
        }

        test("returns Unresolvable(CYCLE) on a longer cycle (A -> B -> A)").config(timeout = 2.seconds) {
            val a = LazyConnectionDataSourceProxy()
            val b = LazyConnectionDataSourceProxy()
            a.setTargetDataSource(b)
            b.setTargetDataSource(a)
            val result = OutboxAutoConfiguration.unwrapDataSource(a)
            result.shouldBeInstanceOf<OutboxAutoConfiguration.Companion.Unwrapped.Unresolvable>()
            result.stoppedAt shouldBeSameInstanceAs a
            result.reason shouldBe OutboxAutoConfiguration.Companion.Unwrapped.Reason.CYCLE
        }
    }
})

// Mimics JpaTransactionManager / HibernateTransactionManager: implements ResourceTransactionManager
// but exposes a non-DataSource (EntityManagerFactory / SessionFactory) as the resource factory.
// JtaTransactionManager does NOT implement ResourceTransactionManager — it falls through the
// non-RTM branch alongside Exposed's SpringTransactionManager.
private class JpaLikeRtmTransactionManager(private val resourceFactory: Any) :
    AbstractPlatformTransactionManager(),
    ResourceTransactionManager {
    override fun getResourceFactory(): Any = resourceFactory
    override fun doGetTransaction(): Any = Any()
    override fun doBegin(transaction: Any, definition: TransactionDefinition) {}
    override fun doCommit(status: DefaultTransactionStatus) {}
    override fun doRollback(status: DefaultTransactionStatus) {}
}

@Configuration(proxyBeanMethods = false)
private class CustomRunnerConfiguration {
    @Bean
    fun customRunner(): TransactionRunner = RUNNER

    companion object {
        val RUNNER: TransactionRunner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T = block()
        }
    }
}

@Configuration(proxyBeanMethods = false)
private class CustomTransactionTemplateConfiguration {
    @Bean
    fun customTemplate(ptm: PlatformTransactionManager): org.springframework.transaction.support.TransactionTemplate {
        TEMPLATE.transactionManager = ptm
        return TEMPLATE
    }

    companion object {
        // Distinctive timeout makes this template identifiable in reference-identity assertions.
        val TEMPLATE: org.springframework.transaction.support.TransactionTemplate =
            org.springframework.transaction.support.TransactionTemplate().apply {
                timeout = 42
            }
    }
}

@Configuration(proxyBeanMethods = false)
private class TwoPtmsNoPrimaryUserConfig {
    @Bean
    fun firstTm(): PlatformTransactionManager = DummyTransactionManager()

    @Bean
    fun secondTm(): PlatformTransactionManager = DummyTransactionManager()
}

@Configuration(proxyBeanMethods = false)
private class PrimaryDstAndDummyPtmConfig {
    @Bean
    @Primary
    fun primaryTm(ds: DataSource): PlatformTransactionManager = DataSourceTransactionManager(ds)

    @Bean
    fun secondaryDummyTm(): PlatformTransactionManager = DummyTransactionManager()
}

private class DummyTransactionManager : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()
    override fun doBegin(transaction: Any, definition: org.springframework.transaction.TransactionDefinition) {}
    override fun doCommit(status: DefaultTransactionStatus) {}
    override fun doRollback(status: DefaultTransactionStatus) {}
}

private fun h2DataSource(): DataSource = JdbcDataSource().apply {
    setURL("jdbc:h2:mem:probe-tx-runner-${System.nanoTime()};DB_CLOSE_DELAY=-1")
    user = "sa"
    password = ""
}
