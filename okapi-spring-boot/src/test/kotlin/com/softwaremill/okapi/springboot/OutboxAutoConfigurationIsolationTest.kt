package com.softwaremill.okapi.springboot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import jakarta.transaction.Status
import jakarta.transaction.UserTransaction
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.InvalidIsolationLevelException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.jta.JtaTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * When `okapiTransactionRunner` pins READ COMMITTED (issue #129): only for a MySQL store, only when
 * the template leaves isolation at DEFAULT, and only when the PTM accepts a custom level.
 */
class OutboxAutoConfigurationIsolationTest : FunSpec({

    val ds: DataSource = SimpleDriverDataSource()

    fun runner(store: () -> OutboxStore) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(DataSource::class.java, { ds })
        .withBean(OutboxStore::class.java, store)
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })

    val mysqlRunner = runner { MysqlOutboxStore(SpringConnectionProvider(ds)) }

    fun captureWarnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(OutboxAutoConfiguration::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }
    }

    test("MySQL store: the auto-built TransactionTemplate runs at READ COMMITTED") {
        mysqlRunner
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .run { ctx ->
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                runner.transactionTemplate.isolationLevel shouldBe TransactionDefinition.ISOLATION_READ_COMMITTED
            }
    }

    test("MySQL store: a user TransactionTemplate at DEFAULT isolation is copied, keeping its settings, not mutated") {
        mysqlRunner
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .withUserConfiguration(DefaultIsolationTemplateConfiguration::class.java)
            .run { ctx ->
                val template = ctx.getBean(TransactionTemplate::class.java)
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                runner.transactionTemplate.isolationLevel shouldBe TransactionDefinition.ISOLATION_READ_COMMITTED
                runner.transactionTemplate.timeout shouldBe 42
                runner.transactionTemplate.transactionManager shouldBeSameInstanceAs template.transactionManager
                withClue("the user's shared template bean itself must not be mutated") {
                    template.isolationLevel shouldBe TransactionDefinition.ISOLATION_DEFAULT
                }
            }
    }

    test("MySQL store: a user TransactionTemplate with an explicit isolation level is used verbatim") {
        mysqlRunner
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .withUserConfiguration(SerializableTemplateConfiguration::class.java)
            .run { ctx ->
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                runner.transactionTemplate shouldBeSameInstanceAs ctx.getBean(TransactionTemplate::class.java)
                runner.transactionTemplate.isolationLevel shouldBe TransactionDefinition.ISOLATION_SERIALIZABLE
            }
    }

    test("non-MySQL store: isolation is left at DEFAULT and a user template is used verbatim") {
        runner { stubStore() }
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .withUserConfiguration(DefaultIsolationTemplateConfiguration::class.java)
            .run { ctx ->
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                runner.transactionTemplate shouldBeSameInstanceAs ctx.getBean(TransactionTemplate::class.java)
                runner.transactionTemplate.isolationLevel shouldBe TransactionDefinition.ISOLATION_DEFAULT
            }
    }

    test("premise: JtaTransactionManager rejects READ COMMITTED by default, so pinning it would fail every tick") {
        val jta = JtaTransactionManager(FakeUserTransaction()).apply { afterPropertiesSet() }
        shouldThrow<InvalidIsolationLevelException> {
            TransactionTemplate(jta).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }.execute { }
        }
    }

    test("MySQL store + JtaTransactionManager: isolation is not pinned, ticks still run, and a WARN explains the fix") {
        val warnings = captureWarnings {
            mysqlRunner
                .withBean(PlatformTransactionManager::class.java, { JtaTransactionManager(FakeUserTransaction()) })
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()
                    val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                    runner.transactionTemplate.isolationLevel shouldBe TransactionDefinition.ISOLATION_DEFAULT
                    runner.runInTransaction { "tick" } shouldBe "tick"
                }
        }
        val warning = warnings.filter { it.formattedMessage.contains("READ COMMITTED") }.shouldHaveSize(1).single()
        warning.formattedMessage shouldContain JtaTransactionManager::class.java.name
        warning.formattedMessage shouldContain "allowCustomIsolationLevels"
        warning.formattedMessage shouldContain "ISOLATION_READ_COMMITTED"
    }

    test("non-MySQL store + JtaTransactionManager: no isolation WARN (nothing to pin)") {
        val warnings = captureWarnings {
            runner { stubStore() }
                .withBean(PlatformTransactionManager::class.java, { JtaTransactionManager(FakeUserTransaction()) })
                .run { ctx -> ctx.startupFailure.shouldBeNull() }
        }
        warnings.filter { it.formattedMessage.contains("READ COMMITTED") }.shouldBeEmpty()
    }
})

/** Just enough of a JTA `UserTransaction` for `JtaTransactionManager` to begin and commit. */
private class FakeUserTransaction : UserTransaction {
    private var status = Status.STATUS_NO_TRANSACTION
    override fun begin() {
        status = Status.STATUS_ACTIVE
    }
    override fun commit() {
        status = Status.STATUS_NO_TRANSACTION
    }
    override fun rollback() {
        status = Status.STATUS_NO_TRANSACTION
    }
    override fun setRollbackOnly() {
        status = Status.STATUS_MARKED_ROLLBACK
    }
    override fun getStatus(): Int = status
    override fun setTransactionTimeout(seconds: Int) = Unit
}

@Configuration(proxyBeanMethods = false)
private class DefaultIsolationTemplateConfiguration {
    @Bean
    fun defaultIsolationTemplate(ptm: PlatformTransactionManager) = TransactionTemplate(ptm).apply { timeout = 42 }
}

@Configuration(proxyBeanMethods = false)
private class SerializableTemplateConfiguration {
    @Bean
    fun serializableTemplate(ptm: PlatformTransactionManager) = TransactionTemplate(ptm).apply {
        isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE
    }
}
