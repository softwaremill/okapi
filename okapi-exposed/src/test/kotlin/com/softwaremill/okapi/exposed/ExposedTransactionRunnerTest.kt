package com.softwaremill.okapi.exposed

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.core.RouteAwareOutboxStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private val stubInfo = object : DeliveryInfo {
    override val type = "stub"
    override fun serialize() = "{}"
}

/** Hands out one PENDING entry per claim and fails every status update, like a deadlock victim. */
private class FailingUpdateStore : RouteAwareOutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int): List<OutboxEntry> = error("not used")
    override fun claimPending(deliveryTypes: Set<String>, limit: Int, now: Instant): List<OutboxEntry> =
        listOf(OutboxEntry.createPending(OutboxMessage("e", "{}"), stubInfo, Instant.EPOCH))
    override fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry = throw SQLException("Deadlock found", "40001")
    override fun updateAfterProcessingBatch(entries: List<OutboxEntry>): List<OutboxEntry> = throw SQLException("Deadlock found", "40001")
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}

class ExposedTransactionRunnerTest : FunSpec({

    val db = Database.connect(
        "jdbc:h2:mem:runner_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )

    fun isolationSeenBy(runner: ExposedTransactionRunner): Int = runner.runInTransaction {
        (TransactionManager.current().connection.connection as Connection).transactionIsolation
    }

    // The MySQL default (READ COMMITTED, #129) is covered against a real MySQL in
    // okapi-integration-tests' MysqlClaimDoesNotBlockPublishersTest.
    test("keeps the database's default isolation on a non-MySQL database") {
        // H2's own default is already READ COMMITTED, so configure another one to tell them apart.
        val serializableDb = Database.connect(
            "jdbc:h2:mem:runner_test_serializable_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            databaseConfig = DatabaseConfig { defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE },
        )
        isolationSeenBy(ExposedTransactionRunner(serializableDb)) shouldBe Connection.TRANSACTION_SERIALIZABLE
    }

    test("honours an explicit READ COMMITTED") {
        isolationSeenBy(ExposedTransactionRunner(db, Connection.TRANSACTION_READ_COMMITTED)) shouldBe
            Connection.TRANSACTION_READ_COMMITTED
    }

    test("honours an explicit isolation level") {
        isolationSeenBy(ExposedTransactionRunner(db, Connection.TRANSACTION_SERIALIZABLE)) shouldBe
            Connection.TRANSACTION_SERIALIZABLE
    }

    test("null falls back to the database's default isolation") {
        isolationSeenBy(ExposedTransactionRunner(db, null)) shouldBe db.transactionManager.defaultIsolationLevel
    }

    // Issue #134: Exposed's default retry would re-run claim + delivery on an SQLException.
    test("runs a block that throws SQLException once, whatever the database's default max attempts") {
        val retryingDb = Database.connect(
            "jdbc:h2:mem:runner_test_retry_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            databaseConfig = DatabaseConfig { defaultMaxAttempts = 5 },
        )
        val runs = AtomicInteger()

        shouldThrow<SQLException> {
            ExposedTransactionRunner(retryingDb).runInTransaction {
                runs.incrementAndGet()
                throw SQLException("connection reset", "08S01")
            }
        }

        runs.get() shouldBe 1
    }

    test("a failed status update does not deliver the batch again") {
        val deliveries = AtomicInteger()
        val processor = OutboxProcessor(
            store = FailingUpdateStore(),
            entryProcessor = OutboxEntryProcessor(
                object : MessageDeliverer {
                    override val type = stubInfo.type
                    override fun deliver(entry: OutboxEntry): DeliveryResult {
                        deliveries.incrementAndGet()
                        return DeliveryResult.Success
                    }
                },
                RetryPolicy(maxRetries = 1),
                Clock.systemUTC(),
            ),
        )

        shouldThrow<SQLException> { ExposedTransactionRunner(db).runInTransaction { processor.processBatch(10) } }

        deliveries.get() shouldBe 1
    }

    test("a nested call leaves the caller's transaction retries alone") {
        val attempts = AtomicInteger()

        shouldThrow<SQLException> {
            transaction(db) {
                attempts.incrementAndGet()
                ExposedTransactionRunner(db).runInTransaction { }
                throw SQLException("connection reset", "08S01")
            }
        }

        attempts.get() shouldBe db.transactionManager.defaultMaxAttempts
    }

    test("a block that succeeds commits and returns its result") {
        ExposedTransactionRunner(db).runInTransaction { 42 } shouldBe 42
    }
})
