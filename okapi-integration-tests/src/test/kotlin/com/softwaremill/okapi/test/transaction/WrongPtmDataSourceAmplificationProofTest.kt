package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.springboot.OkapiLiquibaseAutoConfiguration
import com.softwaremill.okapi.springboot.OutboxAutoConfiguration
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.springboot.SpringOutboxPublisher
import com.softwaremill.okapi.test.support.RecordingMessageDeliverer
import com.softwaremill.okapi.test.support.pgDataSourceOf
import com.softwaremill.okapi.test.support.runOkapiLiquibaseOn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Documents the residual silent-failure risk for non-extractable PTMs (JTA, Exposed bridge,
 * any `PlatformTransactionManager` that exposes neither a `DataSource` resourceFactory nor a
 * public `getDataSource()`) when the user wires the outbox to a different DataSource than the
 * PTM and does NOT set `okapi.transaction-manager-qualifier`.
 *
 * `validatePtmDataSourceMatch` can fail-fast for `DataSourceTransactionManager`,
 * `JpaTransactionManager`, and `HibernateTransactionManager` (see `extractDataSource`). For the
 * remaining PTM families it can only emit a WARN — Spring exposes no public API to derive the
 * bound DataSource. This test pins down what "WARN only" empirically means: concurrent processors
 * see fully unlocked rows and amplify delivery.
 *
 * Asserts amplification DID happen (50/50 entries delivered more than once is the typical run).
 * If a future change adds extraction support for the Exposed bridge — or pessimistic locking
 * starts holding across the spurious auto-commit — this test will fail and force a re-evaluation.
 */
class WrongPtmDataSourceAmplificationProofTest : FunSpec({

    val dsAContainer = PostgreSQLContainer<Nothing>("postgres:16")
    val dsBContainer = PostgreSQLContainer<Nothing>("postgres:16")

    lateinit var dsA: DataSource
    lateinit var dsB: DataSource

    beforeSpec {
        dsAContainer.start()
        dsBContainer.start()
        dsA = pgDataSourceOf(dsAContainer)
        dsB = pgDataSourceOf(dsBContainer)
        // Migrate both: DS-B holds the outbox table the processor reads; DS-A would have it if
        // okapi had picked it. Keeping parity rules out "table missing" as a cause of zero deliveries.
        runOkapiLiquibaseOn(dsAContainer)
        runOkapiLiquibaseOn(dsBContainer)
    }

    afterSpec {
        dsAContainer.stop()
        dsBContainer.stop()
    }

    test("non-extractable PTM bound to wrong DataSource permits delivery amplification") {
        val recorder = RecordingMessageDeliverer()

        // Publish 50 entries to DS-B via a CORRECTLY-wired DST(DS-B) publisher.
        val publishTpl = TransactionTemplate(DataSourceTransactionManager(dsB))
        val publishStore = PostgresOutboxStore(SpringConnectionProvider(dsB), java.time.Clock.systemUTC())
        val publisher = SpringOutboxPublisher(
            delegate = com.softwaremill.okapi.core.OutboxPublisher(publishStore, java.time.Clock.systemUTC()),
            dataSource = dsB,
        )
        repeat(50) { i ->
            publishTpl.execute {
                publisher.publish(OutboxMessage("test.event", """{"i":$i}"""), AmplificationDeliveryInfo)
            }
        }

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
            // DS-B as @Primary so resolveDataSource() picks it as the outbox DS.
            .withBean("dsB", DataSource::class.java, { dsB }, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean("dsA", DataSource::class.java, { dsA })
            // Exposed SpringTransactionManager bound to DS-A — non-extractable: validatePtmDataSourceMatch
            // logs a WARN and proceeds (the silent-failure setup this test documents).
            .withBean("exposedTmA", PlatformTransactionManager::class.java, { SpringTransactionManager(dsA) })
            .withBean(MessageDeliverer::class.java, { recorder })
            .withBean(PostgresOutboxStore::class.java, {
                PostgresOutboxStore(SpringConnectionProvider(dsB), java.time.Clock.systemUTC())
            })
            .withPropertyValues("okapi.processor.enabled=false", "okapi.liquibase.enabled=false")
            .run { ctx ->
                val processor = ctx.getBean(OutboxProcessor::class.java)
                val transactionRunner = ctx.getBean(TransactionRunner::class.java)

                val barrier = CyclicBarrier(5)
                val executor = Executors.newVirtualThreadPerTaskExecutor()
                val futures = (1..5).map {
                    CompletableFuture.supplyAsync(
                        {
                            barrier.await(10, TimeUnit.SECONDS)
                            transactionRunner.runInTransaction { processor.processNext(50) }
                        },
                        executor,
                    )
                }
                CompletableFuture.allOf(*futures.toTypedArray()).get(60, TimeUnit.SECONDS)
                executor.shutdown()

                recorder.deliveryCount() shouldBe 50
                // The residual risk: with FOR UPDATE SKIP LOCKED collapsed to auto-commit, concurrent
                // processors see overlapping result sets and at least one entry is delivered twice.
                recorder.deliveries.filter { it.value.size > 1 }.shouldNotBeEmpty()
            }
    }
})

private object AmplificationDeliveryInfo : DeliveryInfo {
    override val type: String = "recording"
    override fun serialize(): String = """{"type":"recording"}"""
}
