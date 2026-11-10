package com.softwaremill.okapi.test.e2e

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxProcessorListener
import com.softwaremill.okapi.core.OutboxScheduler
import com.softwaremill.okapi.core.OutboxSchedulerConfig
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.core.RouteAwareOutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.JdbcConnectionProvider
import com.softwaremill.okapi.test.support.MysqlTestSupport
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private object DrainDeliveryInfo : DeliveryInfo {
    override val type: String = "drain"
    override fun serialize(): String = """{"type":"drain"}"""
}

private const val BACKLOG = 250
private const val BATCH_SIZE = 10

/**
 * Issue #132 against real databases: with an interval far longer than the test, a single tick
 * delivers the whole backlog, one transaction per batch.
 */
class DrainBacklogEndToEndTest : FunSpec({
    val postgres = PostgresTestSupport()
    val mysql = MysqlTestSupport()

    beforeSpec {
        postgres.start()
        mysql.start()
    }
    afterSpec {
        postgres.stop()
        mysql.stop()
    }

    fun drainsInOneTick(jdbc: JdbcConnectionProvider, store: RouteAwareOutboxStore, isolation: Int?) {
        val now = Clock.systemUTC().instant()
        jdbc.withTransaction {
            repeat(BACKLOG) { store.persist(OutboxEntry.createPending(OutboxMessage("e", "{}"), DrainDeliveryInfo, now)) }
        }

        val batches = Collections.synchronizedList(mutableListOf<Int>())
        val drained = CountDownLatch(1)
        val listener = object : OutboxProcessorListener {
            override fun onBatchProcessed(processedCount: Int, duration: Duration) {
                batches += processedCount
                if (processedCount < BATCH_SIZE) drained.countDown()
            }
        }
        val processor = OutboxProcessor(
            store = store,
            entryProcessor = OutboxEntryProcessor(
                object : MessageDeliverer {
                    override val type = DrainDeliveryInfo.type
                    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
                },
                RetryPolicy(maxRetries = 0),
                Clock.systemUTC(),
            ),
            listener = listener,
        )
        val runner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T = jdbc.withTransaction(isolation, block)
        }
        val scheduler = OutboxScheduler(processor, runner, OutboxSchedulerConfig(interval = Duration.ofHours(1), batchSize = BATCH_SIZE))

        scheduler.start()
        drained.await(30, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        jdbc.withTransaction { store.countByStatuses() }[OutboxStatus.DELIVERED] shouldBe BACKLOG.toLong()
        // 25 full batches, then an empty claim ends the tick.
        batches shouldContainExactly List(BACKLOG / BATCH_SIZE) { BATCH_SIZE } + 0
    }

    test("Postgres: one tick drains a backlog of $BACKLOG entries") {
        postgres.truncate()
        drainsInOneTick(postgres.jdbc, PostgresOutboxStore(postgres.jdbc), isolation = null)
    }

    test("MySQL: one tick drains a backlog of $BACKLOG entries") {
        mysql.truncate()
        // The scheduler's isolation on MySQL (issue #129).
        drainsInOneTick(mysql.jdbc, MysqlOutboxStore(mysql.jdbc), Connection.TRANSACTION_READ_COMMITTED)
    }
})
