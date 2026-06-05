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
import com.softwaremill.okapi.springboot.SpringTransactionRunner
import com.softwaremill.okapi.test.support.CountingDataSource
import com.softwaremill.okapi.test.support.RecordingMessageDeliverer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Proves [OutboxAutoConfiguration]'s `okapiTransactionRunner` bean factory works with a
 * non-`DataSourceTransactionManager` `PlatformTransactionManager` — specifically Exposed's
 * `SpringTransactionManager` bridge. If the autoconfig assumed DST, this test would fail.
 *
 * Two structural assertions:
 * 1. With processor disabled: a single Spring TX wrapping `springOutboxPublisher.publish()`
 *    borrows exactly one physical connection — proving the autoconfig-built runner bridges to
 *    `SpringConnectionProvider` through Spring's `ConnectionHolder`.
 * 2. With processor enabled: an entry published inside a Spring TX is later delivered by the
 *    background scheduler — proving each tick is bracketed by the bridged PTM.
 */
class ExposedSpringBridgeEndToEndTest : FunSpec({

    val container = PostgreSQLContainer<Nothing>("postgres:16")
    lateinit var counter: CountingDataSource

    beforeSpec {
        container.start()
        val raw = PGSimpleDataSource().apply {
            setURL(container.jdbcUrl)
            user = container.username
            password = container.password
        }
        counter = CountingDataSource(raw)
    }

    afterSpec { container.stop() }

    // Both okapi-postgres and okapi-mysql are on the test classpath (shared integration-tests
    // module). Explicitly register PostgresOutboxStore so the autoconfig's MySQL path is
    // unambiguously skipped — otherwise MySQL's `FOR UPDATE SKIP LOCKED` with `FORCE INDEX`
    // hint would fail on Postgres.
    fun runner(recorder: RecordingMessageDeliverer): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiLiquibaseAutoConfiguration::class.java))
        .withBean(DataSource::class.java, { counter as DataSource })
        .withBean(MessageDeliverer::class.java, { recorder })
        .withBean(PlatformTransactionManager::class.java, { SpringTransactionManager(counter) })
        .withBean(PostgresOutboxStore::class.java, {
            PostgresOutboxStore(SpringConnectionProvider(counter))
        })

    test("publish inside Spring TX driven by Exposed-bridge PTM uses a single physical connection") {
        // Disable processor only — purger is left at its default 1h interval so it never ticks
        // during this test, but its enabled=true keeps `okapiTransactionRunner` factory active
        // (the factory is gated on at least one scheduler being enabled).
        runner(RecordingMessageDeliverer())
            .withPropertyValues("okapi.processor.enabled=false")
            .run { ctx ->
                ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()

                resetCounterAndTruncate(counter)

                val publisher = ctx.getBean(SpringOutboxPublisher::class.java)
                val tm = ctx.getBean(PlatformTransactionManager::class.java)

                TransactionTemplate(tm).execute {
                    publisher.publish(
                        OutboxMessage("order.created", """{"orderId":"abc-123"}"""),
                        RecordingDeliveryInfo,
                    )
                }

                counter.opened.get() shouldBe counter.closed.get()
                counter.opened.get() shouldBe 1
            }
    }

    test("processor tick under Exposed-bridge PTM claims and delivers a published entry") {
        val recorder = RecordingMessageDeliverer()
        runner(recorder)
            .withPropertyValues("okapi.processor.interval=200ms")
            .run { ctx ->
                resetCounterAndTruncate(counter)

                val publisher = ctx.getBean(SpringOutboxPublisher::class.java)
                val tm = ctx.getBean(PlatformTransactionManager::class.java)

                TransactionTemplate(tm).execute {
                    publisher.publish(
                        OutboxMessage("order.created", """{"orderId":"xyz-789"}"""),
                        RecordingDeliveryInfo,
                    )
                }

                val deadline = System.currentTimeMillis() + 5_000
                while (recorder.deliveryCount() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                recorder.deliveryCount() shouldBe 1
            }
    }

    // Happy-path tests above would silently pass even if the autoconfig had re-introduced an
    // auto-commit fallback. This test exercises contention: 5 concurrent processor invocations
    // against 50 published entries. With proper TX bracketing, FOR UPDATE SKIP LOCKED holds
    // across claim+update — no amplification. With a no-op TR the lock releases between claim
    // and update, multiple processors deliver the same entry, and `assertNoAmplification` throws.
    test("autoconfig-built TransactionRunner prevents delivery amplification under concurrent processor invocations") {
        val recorder = RecordingMessageDeliverer()
        runner(recorder)
            // Processor disabled; purger stays at its default 1h interval, keeping the factory active.
            .withPropertyValues("okapi.processor.enabled=false")
            .run { ctx ->
                resetCounterAndTruncate(counter)

                val publisher = ctx.getBean(SpringOutboxPublisher::class.java)
                val tm = ctx.getBean(PlatformTransactionManager::class.java)
                val processor = ctx.getBean(OutboxProcessor::class.java)
                val transactionRunner = ctx.getBean(TransactionRunner::class.java)

                val entryCount = 50
                val processorCount = 5

                repeat(entryCount) { i ->
                    TransactionTemplate(tm).execute {
                        publisher.publish(
                            OutboxMessage("test.event", """{"i":$i}"""),
                            RecordingDeliveryInfo,
                        )
                    }
                }

                val barrier = CyclicBarrier(processorCount)
                val executor = Executors.newVirtualThreadPerTaskExecutor()
                val futures = (1..processorCount).map {
                    CompletableFuture.supplyAsync(
                        {
                            barrier.await(10, TimeUnit.SECONDS)
                            transactionRunner.runInTransaction { processor.processNext(entryCount) }
                        },
                        executor,
                    )
                }
                CompletableFuture.allOf(*futures.toTypedArray()).get(30, TimeUnit.SECONDS)
                executor.shutdown()

                recorder.assertNoAmplification()
                recorder.deliveryCount() shouldBe entryCount
            }
    }

    // Purger uses a different code path than the processor — native SQL delete with limit, no
    // claim/update state machine. Under the Exposed `SpringTransactionManager` bridge this needs
    // its own E2E coverage: a regression where the bridge mishandles bracketing of the bulk delete
    // (e.g. an Exposed upgrade that changes statement execution) would silently leave DELIVERED
    // rows accumulating without breaking any other test.
    test("purger tick under Exposed-bridge PTM removes DELIVERED entries past retention") {
        val recorder = RecordingMessageDeliverer()
        runner(recorder)
            .withPropertyValues(
                "okapi.processor.interval=100ms",
                "okapi.purger.interval=200ms",
                "okapi.purger.retention=1ms",
            )
            .run { ctx ->
                resetCounterAndTruncate(counter)

                val publisher = ctx.getBean(SpringOutboxPublisher::class.java)
                val tm = ctx.getBean(PlatformTransactionManager::class.java)

                TransactionTemplate(tm).execute {
                    publisher.publish(
                        OutboxMessage("test.purger", """{"k":"v"}"""),
                        RecordingDeliveryInfo,
                    )
                }

                val deliveredDeadline = System.currentTimeMillis() + 5_000
                while (recorder.deliveryCount() == 0 && System.currentTimeMillis() < deliveredDeadline) {
                    Thread.sleep(50)
                }
                recorder.deliveryCount() shouldBe 1

                val purgedDeadline = System.currentTimeMillis() + 5_000
                while (rowCount(counter) > 0 && System.currentTimeMillis() < purgedDeadline) {
                    Thread.sleep(100)
                }
                rowCount(counter) shouldBe 0
            }
    }
})

private fun rowCount(counter: CountingDataSource): Int = counter.delegate.connection.use { c ->
    c.createStatement().use { stmt ->
        stmt.executeQuery("SELECT COUNT(*) FROM okapi_outbox").use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }
}

private fun resetCounterAndTruncate(counter: CountingDataSource) {
    counter.delegate.connection.use { c ->
        c.createStatement().use { it.execute("TRUNCATE TABLE okapi_outbox") }
    }
    counter.opened.set(0)
    counter.closed.set(0)
}

private object RecordingDeliveryInfo : DeliveryInfo {
    override val type: String = "recording"
    override fun serialize(): String = """{"type":"recording"}"""
}
