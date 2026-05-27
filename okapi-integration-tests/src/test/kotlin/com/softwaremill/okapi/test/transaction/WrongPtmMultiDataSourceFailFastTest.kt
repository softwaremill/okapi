package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.springboot.OkapiLiquibaseAutoConfiguration
import com.softwaremill.okapi.springboot.OutboxAutoConfiguration
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.test.support.RecordingMessageDeliverer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * A non-extractable PTM (Exposed bridge, JTA) combined with multiple `DataSource` beans and no
 * `okapi.transaction-manager-qualifier` is refused at startup — okapi cannot prove which DS the
 * PTM brackets, so it fails fast rather than risk silent duplicate delivery.
 */
class WrongPtmMultiDataSourceFailFastTest : FunSpec({

    test("non-extractable PTM + multiple DataSource beans + no qualifier fails fast at startup") {
        val dsA: DataSource = SimpleDriverDataSource()
        val dsB: DataSource = SimpleDriverDataSource()

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java),
            )
            // DS-B as @Primary so resolveDataSource() picks it as the outbox DS.
            .withBean("dsB", DataSource::class.java, { dsB }, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean("dsA", DataSource::class.java, { dsA })
            .withBean("exposedTmA", PlatformTransactionManager::class.java, { SpringTransactionManager(dsA) })
            .withBean(MessageDeliverer::class.java, { RecordingMessageDeliverer() })
            .withBean(PostgresOutboxStore::class.java, {
                PostgresOutboxStore(SpringConnectionProvider(dsB), java.time.Clock.systemUTC())
            })
            .withPropertyValues("okapi.processor.enabled=false", "okapi.liquibase.enabled=false")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
                ctx.startupFailure!!.stackTraceToString() shouldContain
                    "Cannot verify the PTM↔DataSource binding for a non-extractable"
            }
    }

    test("okapi.datasource-qualifier alone is NOT sufficient — fail-fast still fires (PTM-side ambiguity remains)") {
        val dsA: DataSource = SimpleDriverDataSource()
        val dsB: DataSource = SimpleDriverDataSource()

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java),
            )
            .withBean("dsB", DataSource::class.java, { dsB }, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean("dsA", DataSource::class.java, { dsA })
            .withBean("exposedTmA", PlatformTransactionManager::class.java, { SpringTransactionManager(dsA) })
            .withBean(MessageDeliverer::class.java, { RecordingMessageDeliverer() })
            .withBean(PostgresOutboxStore::class.java, {
                PostgresOutboxStore(SpringConnectionProvider(dsB), java.time.Clock.systemUTC())
            })
            .withPropertyValues(
                "okapi.processor.enabled=false",
                "okapi.liquibase.enabled=false",
                // User named the outbox DataSource but NOT the PTM. The PTM-side ambiguity remains,
                // so fail-fast must still fire — datasource-qualifier alone does not grant immunity.
                "okapi.datasource-qualifier=dsB",
            )
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
                ctx.startupFailure!!.stackTraceToString() shouldContain
                    "Cannot verify the PTM↔DataSource binding for a non-extractable"
            }
    }

    test("setting okapi.transaction-manager-qualifier lets the same setup start cleanly (escape hatch)") {
        val dsA: DataSource = SimpleDriverDataSource()
        val dsB: DataSource = SimpleDriverDataSource()

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java),
            )
            .withBean("dsB", DataSource::class.java, { dsB }, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean("dsA", DataSource::class.java, { dsA })
            .withBean("exposedTmA", PlatformTransactionManager::class.java, { SpringTransactionManager(dsA) })
            .withBean(MessageDeliverer::class.java, { RecordingMessageDeliverer() })
            .withBean(PostgresOutboxStore::class.java, {
                PostgresOutboxStore(SpringConnectionProvider(dsB), java.time.Clock.systemUTC())
            })
            .withPropertyValues(
                "okapi.processor.enabled=false",
                "okapi.liquibase.enabled=false",
                "okapi.transaction-manager-qualifier=exposedTmA",
            )
            .run { ctx ->
                // Qualifier names the PTM explicitly; autoconfig trusts the user and no longer refuses.
                ctx.startupFailure?.let { error("Expected clean startup, got: ${it.message}") }
            }
    }
})
