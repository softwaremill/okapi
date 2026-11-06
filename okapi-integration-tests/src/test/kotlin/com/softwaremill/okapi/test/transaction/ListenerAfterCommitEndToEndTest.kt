package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessingEvent
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxProcessorListener
import com.softwaremill.okapi.core.OutboxScheduler
import com.softwaremill.okapi.core.OutboxSchedulerConfig
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.exposed.ExposedConnectionProvider
import com.softwaremill.okapi.exposed.ExposedTransactionRunner
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.springboot.SpringTransactionRunner
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private object AfterCommitDeliveryInfo : DeliveryInfo {
    override val type: String = "after-commit"
    override fun serialize(): String = """{"type":"after-commit"}"""
}

/**
 * Issue #137 against a real database: when the scheduler's listener hears that an entry was
 * delivered, another connection must already see it as DELIVERED, i.e. the transaction committed.
 */
class ListenerAfterCommitEndToEndTest : FunSpec({
    val db = PostgresTestSupport()

    beforeSpec { db.start() }
    afterSpec { db.stop() }

    /** Runs one scheduler tick with [runner] and [store]; returns the status the listener saw from another connection. */
    fun statusSeenByListener(runner: TransactionRunner, store: OutboxStore): String {
        db.truncate()
        val entry = OutboxEntry.createPending(OutboxMessage("e", "{}"), AfterCommitDeliveryInfo, Clock.systemUTC().instant())
        db.jdbc.withTransaction { PostgresOutboxStore(db.jdbc).persist(entry) }

        val seen = CompletableFuture<String>()
        val listener = object : OutboxProcessorListener {
            override fun onEntryProcessed(event: OutboxProcessingEvent) {
                try {
                    // A connection of its own: it only sees committed data.
                    db.dataSource.connection.use { conn ->
                        conn.prepareStatement("SELECT status FROM okapi_outbox WHERE id = ?::uuid").use { stmt ->
                            stmt.setString(1, entry.outboxId.raw.toString())
                            stmt.executeQuery().use { rs ->
                                check(rs.next()) { "row ${entry.outboxId} not found" }
                                seen.complete(rs.getString("status"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    seen.completeExceptionally(e)
                }
            }
        }
        val processor = OutboxProcessor(
            store = store,
            entryProcessor = OutboxEntryProcessor(
                object : MessageDeliverer {
                    override val type = AfterCommitDeliveryInfo.type
                    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
                },
                RetryPolicy(maxRetries = 0),
                Clock.systemUTC(),
            ),
            listener = listener,
        )
        val scheduler = OutboxScheduler(processor, runner, OutboxSchedulerConfig(interval = Duration.ofMinutes(1)))
        scheduler.start()
        try {
            return seen.get(10, TimeUnit.SECONDS)
        } finally {
            scheduler.stop()
        }
    }

    test("plain JDBC runner: the listener sees the delivered status already committed") {
        val runner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T = db.jdbc.withTransaction(block)
        }
        statusSeenByListener(runner, PostgresOutboxStore(db.jdbc)) shouldBe "DELIVERED"
    }

    test("SpringTransactionRunner: the listener sees the delivered status already committed") {
        val runner = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(db.dataSource)))
        statusSeenByListener(runner, PostgresOutboxStore(SpringConnectionProvider(db.dataSource))) shouldBe "DELIVERED"
    }

    test("ExposedTransactionRunner: the listener sees the delivered status already committed") {
        val database = Database.connect(db.dataSource)
        statusSeenByListener(ExposedTransactionRunner(database), PostgresOutboxStore(ExposedConnectionProvider(database))) shouldBe
            "DELIVERED"
    }
})
