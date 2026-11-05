package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.test.support.JdbcConnectionProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.TimeZone

private object TimestampTestDeliveryInfo : DeliveryInfo {
    override val type: String = "timestamp-test"
    override fun serialize(): String = """{"type":"timestamp-test"}"""
}

/**
 * Timestamp columns must hold instants that survive any JVM, session or driver time zone, and dates
 * past 2038 (issues #135, #138). [utcText] renders a column as UTC `yyyy-MM-dd HH:mm:ss.SSSSSS` in
 * SQL, so the tests check what is stored, not only what the store reads back. [setSessionZone] is a
 * statement that moves the session time zone away from UTC.
 */
fun FunSpec.timestampColumnTests(
    dbName: String,
    jdbcProvider: () -> JdbcConnectionProvider,
    storeFactory: () -> OutboxStore,
    startDb: () -> Unit,
    stopDb: () -> Unit,
    truncate: () -> Unit,
    utcText: (column: String) -> String,
    setSessionZone: String,
) {
    beforeSpec { startDb() }
    afterSpec { stopDb() }
    beforeEach { truncate() }

    fun storedUtc(id: String, column: String): String = jdbcProvider().withTransaction {
        jdbcProvider().withConnection { conn ->
            conn.prepareStatement("SELECT ${utcText(column)} AS v FROM okapi_outbox WHERE CAST(id AS CHAR(36)) = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "no row $id" }
                    rs.getString("v")
                }
            }
        }
    }

    fun entry(createdAt: Instant): OutboxEntry =
        OutboxEntry.createPending(OutboxMessage("timestamp.test", "{}"), TimestampTestDeliveryInfo, createdAt)

    test("[$dbName] instants after 2038 are stored and read back") {
        val store = storeFactory()
        val createdAt = Instant.parse("2040-02-29T12:34:56.123456Z")
        val attempted = entry(createdAt).retry(Instant.parse("2100-01-01T00:00:00.000001Z"), "boom")
        jdbcProvider().withTransaction { store.persist(attempted) }

        val claimed = jdbcProvider().withTransaction { store.claimPending(10) }
        claimed.map { it.createdAt } shouldContainExactly listOf(createdAt)
        claimed.single().lastAttempt shouldBe Instant.parse("2100-01-01T00:00:00.000001Z")
        storedUtc(attempted.outboxId.raw.toString(), "created_at") shouldBe "2040-02-29 12:34:56.123456"
    }

    test("[$dbName] the purger cutoff works past 2038") {
        val store = storeFactory()
        val delivered = entry(Instant.parse("2045-01-01T00:00:00Z")).toDelivered(Instant.parse("2045-01-01T00:00:01Z"))
        jdbcProvider().withTransaction { store.persist(delivered) }

        jdbcProvider().withTransaction { store.removeDeliveredBefore(Instant.parse("2045-01-01T00:00:00Z"), 10) } shouldBe 0
        jdbcProvider().withTransaction { store.removeDeliveredBefore(Instant.parse("2045-01-01T00:00:02Z"), 10) } shouldBe 1
    }

    test("[$dbName] values are stored as UTC whatever the JVM and session time zones") {
        val store = storeFactory()
        // Europe/Warsaw falls back at 01:00Z on this day: 00:30Z and 01:30Z are both 02:30 local, so a
        // value stored as local wall-clock time reads back an hour early.
        val createdAt = Instant.parse("2024-10-27T01:30:00.5Z")
        val original = TimeZone.getDefault()
        val pending = entry(createdAt)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
            jdbcProvider().withTransaction {
                jdbcProvider().withConnection { conn -> conn.createStatement().use { it.execute(setSessionZone) } }
                store.persist(pending)
            }
            storedUtc(pending.outboxId.raw.toString(), "created_at") shouldBe "2024-10-27 01:30:00.500000"

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val readBack = jdbcProvider().withTransaction {
                jdbcProvider().withConnection { conn -> conn.createStatement().use { it.execute(setSessionZone) } }
                store.findOldestCreatedAt(setOf(OutboxStatus.PENDING))
            }
            readBack shouldBe mapOf(OutboxStatus.PENDING to createdAt)
            jdbcProvider().withTransaction { store.claimPending(10) }.single().createdAt shouldBe createdAt
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
