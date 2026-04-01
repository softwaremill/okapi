package com.softwaremill.okapi.mysql

import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.MySQLContainer
import java.time.Clock
import java.time.Instant

class MysqlOutboxStoreTest : BehaviorSpec({
    val mysql = MySQLContainer("mysql:8.0").apply { start() }

    val db = Database.connect(
        url = mysql.jdbcUrl,
        driver = mysql.driverClassName,
        user = mysql.username,
        password = mysql.password,
    )

    val clock = Clock.systemUTC()
    val store = MysqlOutboxStore(clock)

    beforeSpec {
        transaction(db) {
            SchemaUtils.create(OutboxTable)
        }
    }

    afterSpec {
        mysql.stop()
    }

    given("persist and claimPending") {
        `when`("an entry is persisted") {
            val entry = newEntry()
            transaction(db) { store.persist(entry) }

            then("claimPending returns it") {
                val claimed = transaction(db) { store.claimPending(10) }
                claimed shouldHaveSize 1
                claimed.first().outboxId shouldBe entry.outboxId
                claimed.first().status shouldBe OutboxStatus.PENDING
            }
        }
    }

    given("updateAfterProcessing") {
        `when`("entry is marked DELIVERED") {
            val entry = newEntry()
            transaction(db) { store.persist(entry) }

            val delivered = entry.copy(status = OutboxStatus.DELIVERED, lastAttempt = Instant.now(clock))
            transaction(db) { store.updateAfterProcessing(delivered) }

            then("claimPending no longer returns it") {
                val claimed = transaction(db) { store.claimPending(10) }
                claimed.none { it.outboxId == entry.outboxId } shouldBe true
            }
        }
    }

    given("removeDeliveredBefore") {
        `when`("called with a cutoff time") {
            transaction(db) { exec("DELETE FROM outbox") }
            val entry = newEntry()
            val delivered = entry.copy(
                status = OutboxStatus.DELIVERED,
                lastAttempt = Instant.parse("2020-01-01T00:00:00Z"),
            )
            transaction(db) { store.persist(delivered) }
            transaction(db) { store.removeDeliveredBefore(Instant.parse("2025-01-01T00:00:00Z"), Int.MAX_VALUE) }

            then("old delivered entries are removed") {
                val counts = transaction(db) { store.countByStatuses() }
                counts[OutboxStatus.DELIVERED] shouldBe 0L
            }
        }
    }

    given("removeDeliveredBefore with limit") {
        `when`("limit is smaller than matching entries") {
            transaction(db) { exec("DELETE FROM outbox") }
            repeat(5) {
                val entry = newEntry().copy(
                    status = OutboxStatus.DELIVERED,
                    lastAttempt = Instant.parse("2020-01-01T00:00:00Z"),
                )
                transaction(db) { store.persist(entry) }
            }

            val deleted = transaction(db) {
                store.removeDeliveredBefore(Instant.parse("2025-01-01T00:00:00Z"), 3)
            }

            then("only deletes up to limit") {
                deleted shouldBe 3
            }
            then("remaining entries still exist") {
                val counts = transaction(db) { store.countByStatuses() }
                counts[OutboxStatus.DELIVERED] shouldBe 2L
            }
        }
    }

    given("countByStatuses") {
        `when`("entries exist with different statuses") {
            transaction(db) {
                exec("DELETE FROM outbox")
                store.persist(newEntry().copy(status = OutboxStatus.PENDING))
                store.persist(newEntry().copy(status = OutboxStatus.PENDING))
                store.persist(
                    newEntry().copy(
                        status = OutboxStatus.DELIVERED,
                        lastAttempt = Instant.now(clock),
                    ),
                )
            }

            then("returns correct counts per status") {
                val counts = transaction(db) { store.countByStatuses() }
                counts[OutboxStatus.PENDING] shouldBe 2L
                counts[OutboxStatus.DELIVERED] shouldBe 1L
                counts[OutboxStatus.FAILED] shouldBe 0L
            }
        }
    }
})

private fun newEntry() = OutboxEntry(
    outboxId = OutboxId.new(),
    messageType = "test.event",
    payload = """{"key": "value"}""",
    deliveryType = "http",
    status = OutboxStatus.PENDING,
    createdAt = Instant.now(),
    updatedAt = Instant.now(),
    retries = 0,
    lastAttempt = null,
    lastError = null,
    deliveryMetadata = """{"type": "http", "url": "http://localhost"}""",
)
