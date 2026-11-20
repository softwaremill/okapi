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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.sql.SQLException
import java.time.Clock

/** Marker in a `last_error` that the test trigger rejects. */
const val REJECTED_ERROR_MARKER = "REJECT_ME"

/** `message_type` whose rows the test trigger rejects on every update, as a rejected value (SQLState 22000). */
const val ALWAYS_REJECTED_TYPE = "always-rejected"

/** `message_type` whose rows the test trigger fails on every update with a serialization failure (SQLState 40001). */
const val TRANSIENT_FAILURE_TYPE = "transient-failure"

private object IsolationTestDeliveryInfo : DeliveryInfo {
    override val type: String = "isolation-test"
    override fun serialize(): String = """{"type":"isolation-test"}"""
}

/** Delivers `healthy` entries and fails the others; their error carries the trigger's marker, except a transient one's. */
private object HealthyOnlyDeliverer : MessageDeliverer {
    override val type: String = IsolationTestDeliveryInfo.type
    override fun deliver(entry: OutboxEntry): DeliveryResult = when {
        entry.messageType.startsWith("healthy") -> DeliveryResult.Success
        entry.messageType == TRANSIENT_FAILURE_TYPE -> DeliveryResult.RetriableFailure("HTTP 503")
        else -> DeliveryResult.RetriableFailure("HTTP 503 $REJECTED_ERROR_MARKER")
    }
}

private data class IsolationRow(val status: OutboxStatus, val retries: Int, val lastError: String?)

/**
 * Issue #141: a row whose update the database rejects for any reason must not roll back the results
 * of the rest of its batch. [installRejectingTrigger] adds a BEFORE UPDATE trigger that rejects an
 * update whose `last_error` contains [REJECTED_ERROR_MARKER], or of a row whose `message_type` is
 * [ALWAYS_REJECTED_TYPE], standing in for whatever value the database might refuse, and fails an
 * update of a [TRANSIENT_FAILURE_TYPE] row as a serialization failure would, unless it writes okapi's
 * placeholder error, so that taking a transient failure for a rejected value would let the batch commit.
 */
fun FunSpec.rejectedRowIsolationTests(
    dbName: String,
    jdbcProvider: () -> JdbcConnectionProvider,
    storeFactory: () -> OutboxStore,
    startDb: () -> Unit,
    stopDb: () -> Unit,
    truncate: () -> Unit,
    installRejectingTrigger: () -> Unit,
) {
    val clock = Clock.systemUTC()

    beforeSpec {
        startDb()
        installRejectingTrigger()
    }
    afterSpec { stopDb() }
    beforeEach { truncate() }

    fun seed(messageType: String) {
        jdbcProvider().withTransaction {
            storeFactory().persist(OutboxEntry.createPending(OutboxMessage(messageType, "{}"), IsolationTestDeliveryInfo, clock.instant()))
        }
    }

    fun row(messageType: String): IsolationRow = jdbcProvider().withTransaction {
        jdbcProvider().withConnection { conn ->
            conn.prepareStatement("SELECT status, retries, last_error FROM okapi_outbox WHERE message_type = ?").use { stmt ->
                stmt.setString(1, messageType)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "no row for $messageType" }
                    IsolationRow(OutboxStatus.from(rs.getString("status")), rs.getInt("retries"), rs.getString("last_error"))
                }
            }
        }
    }

    fun processor() = OutboxProcessor(
        store = storeFactory(),
        entryProcessor = OutboxEntryProcessor(HealthyOnlyDeliverer, RetryPolicy(maxRetries = 5), clock),
    )

    test("[$dbName] a row whose last_error is rejected keeps its status and retries, and the rest of the batch commits") {
        // More than 3 rows, so that Connector/J rewrites the MySQL batch into one multi-statement.
        val healthy = (1..5).map { "healthy-$it" }
        healthy.forEach { seed(it) }
        seed("poisoned")

        jdbcProvider().withTransaction { processor().processNext(10) } shouldBe 6

        healthy.forEach { row(it).status shouldBe OutboxStatus.DELIVERED }
        val poisoned = row("poisoned")
        poisoned.status shouldBe OutboxStatus.PENDING
        poisoned.retries shouldBe 1
        checkNotNull(poisoned.lastError) shouldStartWith "okapi: the database rejected this entry's error message"
    }

    test("[$dbName] a row rejected whatever its last_error still fails the batch, and nothing is written") {
        seed("healthy")
        seed(ALWAYS_REJECTED_TYPE)

        shouldThrow<SQLException> { jdbcProvider().withTransaction { processor().processNext(10) } }

        row("healthy").status shouldBe OutboxStatus.PENDING
        row(ALWAYS_REJECTED_TYPE).retries shouldBe 0
    }

    test("[$dbName] a transient failure is not taken for a rejected value: the batch fails, and nothing is written") {
        seed("healthy")
        seed(TRANSIENT_FAILURE_TYPE)

        shouldThrow<SQLException> { jdbcProvider().withTransaction { processor().processNext(10) } }.sqlState shouldBe "40001"

        row("healthy").status shouldBe OutboxStatus.PENDING
        row(TRANSIENT_FAILURE_TYPE) shouldBe IsolationRow(OutboxStatus.PENDING, 0, null)
    }

    test("[$dbName] a batch with no rejected row is written as before") {
        seed("healthy")
        seed("healthy-2")

        jdbcProvider().withTransaction { processor().processNext(10) } shouldBe 2

        row("healthy") shouldBe IsolationRow(OutboxStatus.DELIVERED, 0, null)
        row("healthy-2") shouldBe IsolationRow(OutboxStatus.DELIVERED, 0, null)
    }
}
