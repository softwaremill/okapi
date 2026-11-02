package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.test.support.JdbcConnectionProvider
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Clock

private object PoisonTestDeliveryInfo : DeliveryInfo {
    override val type: String = "poison-test"
    override fun serialize(): String = """{"type":"poison-test"}"""
}

/** Delivers `healthy` entries and fails `poisoned` ones with [error]. */
private class SplitDeliverer(private val error: String) : MessageDeliverer {
    override val type: String = PoisonTestDeliveryInfo.type
    override fun deliver(entry: OutboxEntry): DeliveryResult =
        if (entry.messageType == "healthy") DeliveryResult.Success else DeliveryResult.RetriableFailure(error)
}

private data class Row(val status: OutboxStatus, val retries: Int, val lastError: String?)

/**
 * Regression tests for issue #130. Every result of a batch is persisted in one update inside the
 * claim transaction, so a `lastError` the database rejects used to roll back the whole batch:
 * already-delivered entries went back to PENDING with `retries` unchanged and were redelivered on
 * every tick, forever.
 *
 * [rejectedError] is a value this database refuses in `last_error` (over-long for MySQL's `TEXT`,
 * NUL for Postgres). The control test proves it really is rejected, so the processor test passing
 * means the value is sanitized, not that the database happened to accept it.
 */
fun FunSpec.lastErrorPoisonBatchTests(
    dbName: String,
    jdbcProvider: () -> JdbcConnectionProvider,
    storeFactory: () -> OutboxStore,
    startDb: () -> Unit,
    stopDb: () -> Unit,
    truncate: () -> Unit,
    rejectedError: String,
) {
    val clock = Clock.systemUTC()

    beforeSpec { startDb() }
    afterSpec { stopDb() }
    beforeEach { truncate() }

    fun seed(messageType: String): OutboxEntry {
        val entry = OutboxEntry.createPending(OutboxMessage(messageType, "{}"), PoisonTestDeliveryInfo, clock.instant())
        jdbcProvider().withTransaction { storeFactory().persist(entry) }
        return entry
    }

    fun row(messageType: String): Row = jdbcProvider().withTransaction {
        jdbcProvider().withConnection { conn ->
            conn.prepareStatement("SELECT status, retries, last_error FROM okapi_outbox WHERE message_type = ?").use { stmt ->
                stmt.setString(1, messageType)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "no row for $messageType" }
                    Row(OutboxStatus.from(rs.getString("status")), rs.getInt("retries"), rs.getString("last_error"))
                }
            }
        }
    }

    test("[$dbName] control: the database rejects the raw error value") {
        val entry = seed("poisoned")
        val unsanitized = entry.copy(status = OutboxStatus.PENDING, retries = 1, lastError = rejectedError)
        shouldThrowAny {
            jdbcProvider().withTransaction { storeFactory().updateAfterProcessingBatch(listOf(unsanitized)) }
        }
    }

    test("[$dbName] a rejected error value no longer rolls back the delivered entries of its batch") {
        seed("healthy")
        seed("poisoned")
        val processor = OutboxProcessor(
            store = storeFactory(),
            entryProcessor = OutboxEntryProcessor(SplitDeliverer(rejectedError), RetryPolicy(maxRetries = 5), clock),
        )

        jdbcProvider().withTransaction { processor.processNext(10) } shouldBe 2

        row("healthy").status shouldBe OutboxStatus.DELIVERED
        val poisoned = row("poisoned")
        poisoned.status shouldBe OutboxStatus.PENDING
        poisoned.retries shouldBe 1
        val stored = checkNotNull(poisoned.lastError)
        stored.length shouldBeLessThanOrEqual OutboxEntry.MAX_LAST_ERROR_LENGTH
        stored shouldNotContain "\u0000"
    }
}
