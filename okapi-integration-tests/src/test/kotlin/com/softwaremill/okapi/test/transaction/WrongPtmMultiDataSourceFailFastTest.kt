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
 * Replaces the former `WrongPtmDataSourceAmplificationProofTest`, which asserted a positive race
 * outcome (`deliveries.filter { size > 1 }.shouldNotBeEmpty()`) — that assertion was both flaky
 * (race-dependent) and semantically inverted (it would fail once the underlying risk was actually
 * mitigated). The correct fix lives in production code: a non-extractable PTM combined with
 * multiple `DataSource` beans and no `okapi.transaction-manager-qualifier` / `datasource-qualifier`
 * is now refused at startup, eliminating the silent-duplicate residual risk rather than
 * documenting it via a race.
 *
 * Pins the new behaviour deterministically: context refresh must fail with the new error message.
 */
class WrongPtmMultiDataSourceFailFastTest : FunSpec({

    test("non-extractable PTM + multiple DataSource beans + no qualifier fails fast at startup") {
        val dsA: DataSource = SimpleDriverDataSource()
        val dsB: DataSource = SimpleDriverDataSource()

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java),
            )
            // DS-B as @Primary so resolveDataSource() picks it as the outbox DS; DS-A is the
            // ambiguous second DataSource that disambiguation would otherwise resolve.
            .withBean("dsB", DataSource::class.java, { dsB }, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean("dsA", DataSource::class.java, { dsA })
            // Exposed's SpringTransactionManager is non-extractable (no DataSource resourceFactory,
            // no public getDataSource()) — same shape as JtaTransactionManager. With ≥2 DataSource
            // beans and no qualifier set, okapi cannot prove which DS this PTM brackets and refuses
            // to start rather than risk silent duplicate delivery.
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
                // No startup failure: user has explicitly taken responsibility for the binding by
                // naming the PTM. The autoconfig still cannot verify, but no longer refuses.
                ctx.startupFailure?.let { error("Expected clean startup, got: ${it.message}") }
            }
    }
})
