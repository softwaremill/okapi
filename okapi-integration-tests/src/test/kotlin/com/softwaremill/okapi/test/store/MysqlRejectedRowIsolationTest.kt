package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MysqlRejectedRowIsolationTest : FunSpec({
    val db = MysqlTestSupport()

    rejectedRowIsolationTests(
        dbName = "mysql",
        jdbcProvider = { db.jdbc },
        storeFactory = { MysqlOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
        installRejectingTrigger = {
            // With binary logging on, creating a trigger takes SUPER, which only root has.
            DriverManager.getConnection(db.container.jdbcUrl, "root", db.container.password).use { conn ->
                conn.createStatement().use {
                    it.execute(
                        """
                        CREATE TRIGGER okapi_test_reject BEFORE UPDATE ON okapi_outbox FOR EACH ROW
                        BEGIN
                            IF NEW.last_error LIKE '%$REJECTED_ERROR_MARKER%' OR NEW.message_type = '$ALWAYS_REJECTED_TYPE' THEN
                                SIGNAL SQLSTATE '22000' SET MESSAGE_TEXT = 'rejected by test trigger';
                            END IF;
                            IF NEW.message_type = '$TRANSIENT_FAILURE_TYPE' AND COALESCE(NEW.last_error, '') NOT LIKE 'okapi:%' THEN
                                SIGNAL SQLSTATE '40001' SET MESSAGE_TEXT = 'could not serialize (test trigger)';
                            END IF;
                        END
                        """.trimIndent(),
                    )
                }
            }
        },
    )

    // A deadlock victim's whole transaction is rolled back, savepoints included: the store must not go
    // on writing rows outside the claim's transaction, whose locks are gone.
    test("[mysql] a deadlock during the batch update propagates, and no row is written") {
        val store = MysqlOutboxStore(db.jdbc)
        val now = Instant.now()
        val (a, b) = listOf("a", "b").map { OutboxEntry.createPending(OutboxMessage(it, "{}"), DeadlockTestDeliveryInfo, now) }
        val heavy = (1..50).map { OutboxEntry.createPending(OutboxMessage("heavy-$it", "{}"), DeadlockTestDeliveryInfo, now) }
        db.jdbc.withTransaction { (listOf(a, b) + heavy).forEach { store.persist(it) } }

        DriverManager.getConnection(db.container.jdbcUrl, db.container.username, db.container.password).use { other ->
            other.autoCommit = false
            other.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            // Having changed more rows, the other transaction is never InnoDB's choice of deadlock victim.
            other.createStatement().use {
                it.executeUpdate(
                    "UPDATE okapi_outbox SET retries = retries + 1 WHERE message_type LIKE 'heavy-%'",
                )
            }
            other.lockRow(a)

            val failure = shouldThrow<SQLException> {
                db.jdbc.withTransaction {
                    db.jdbc.withConnection { it.lockRow(b) }
                    // Whichever of the two waits first, they end up waiting for each other.
                    val otherWaitsForB = CompletableFuture.runAsync { runCatching { other.lockRow(b) } }
                    try {
                        store.updateAfterProcessingBatch(listOf(a, b).map { it.copy(status = OutboxStatus.DELIVERED) })
                    } finally {
                        otherWaitsForB.get(30, TimeUnit.SECONDS)
                    }
                }
            }
            other.rollback()

            failure.errorCode shouldBe 1213
        }
        db.jdbc.withTransaction { store.countByStatuses() }[OutboxStatus.DELIVERED] shouldBe 0L
    }
})

private object DeadlockTestDeliveryInfo : DeliveryInfo {
    override val type: String = "deadlock-test"
    override fun serialize(): String = """{"type":"deadlock-test"}"""
}

private fun Connection.lockRow(entry: OutboxEntry) = prepareStatement("SELECT id FROM okapi_outbox WHERE id = ? FOR UPDATE").use {
    it.setString(1, entry.outboxId.raw.toString())
    it.executeQuery().close()
}
