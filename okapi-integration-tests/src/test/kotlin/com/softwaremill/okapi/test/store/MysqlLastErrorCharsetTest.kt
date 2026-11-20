package com.softwaremill.okapi.test.store

import com.mysql.cj.jdbc.MysqlDataSource
import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.JdbcConnectionProvider
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock

private const val CHANGELOG = "com/softwaremill/okapi/db/mysql/changelog.xml"

/** Changesets in 001 to 004, i.e. everything before 005. */
private const val CHANGESETS_BEFORE_005 = 7

private const val EMOJI_ERROR = "HTTP 503: upstream is on fire 🔥"

private object CharsetTestDeliveryInfo : DeliveryInfo {
    override val type: String = "charset-test"
    override fun serialize(): String = """{"type":"charset-test"}"""
}

private object EmojiErrorDeliverer : MessageDeliverer {
    override val type: String = CharsetTestDeliveryInfo.type
    override fun deliver(entry: OutboxEntry): DeliveryResult = when (entry.messageType) {
        "healthy" -> DeliveryResult.Success
        else -> DeliveryResult.RetriableFailure(EMOJI_ERROR)
    }
}

/**
 * Issue #141 on a MySQL schema whose default character set is latin1, as on servers upgraded from
 * 5.7. 001 creates `okapi_outbox` in the schema's character set, where an emoji in `last_error` is
 * rejected; changeset 005 converts the column to utf8mb4.
 */
class MysqlLastErrorCharsetTest : FunSpec({
    val container = MySQLContainer<Nothing>("mysql:8.0")
    val clock = Clock.systemUTC()

    beforeSpec {
        container.start()
        DriverManager.getConnection(container.jdbcUrl, "root", container.password).use { conn ->
            conn.createStatement().use {
                it.execute("CREATE DATABASE legacy_before_005 CHARACTER SET latin1")
                it.execute("CREATE DATABASE legacy_upgraded CHARACTER SET latin1")
                it.execute("CREATE DATABASE modern")
            }
        }
    }
    afterSpec { container.stop() }

    fun url(database: String) = container.jdbcUrl.replaceAfterLast('/', database)

    fun connect(database: String): Connection = DriverManager.getConnection(url(database), "root", container.password)

    /** Runs the first [changes] changesets (all of them when null). */
    fun migrate(database: String, changes: Int? = null) {
        connect(database).use { conn ->
            Liquibase(
                CHANGELOG,
                ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(conn)),
            ).use { if (changes != null) it.update(changes, "") else it.update("") }
        }
    }

    fun jdbc(database: String) = JdbcConnectionProvider(
        MysqlDataSource().apply {
            setURL(url(database))
            user = "root"
            setPassword(container.password)
        },
    )

    fun query(database: String, sql: String): String? = connect(database).use { conn ->
        conn.createStatement().use { stmt -> stmt.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
    }

    fun lastErrorCharset(database: String) = query(
        database,
        "SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'okapi_outbox' AND COLUMN_NAME = 'last_error'",
    )

    fun execType005(database: String) = query(database, "SELECT EXECTYPE FROM DATABASECHANGELOG WHERE ID = '005'")

    /** Seeds a `healthy` and a `poisoned` entry, processes them in one batch and returns the stored rows. */
    fun processHealthyAndPoisoned(database: String): Map<String, Pair<OutboxStatus, String?>> {
        connect(database).use { conn -> conn.createStatement().use { it.execute("DELETE FROM okapi_outbox") } }
        val jdbc = jdbc(database)
        val store = MysqlOutboxStore(jdbc)
        jdbc.withTransaction {
            listOf("healthy", "poisoned").forEach {
                store.persist(OutboxEntry.createPending(OutboxMessage(it, "{}"), CharsetTestDeliveryInfo, clock.instant()))
            }
        }
        val processor = OutboxProcessor(store, OutboxEntryProcessor(EmojiErrorDeliverer, RetryPolicy(maxRetries = 5), clock))
        jdbc.withTransaction { processor.processNext(10) } shouldBe 2

        return connect(database).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT message_type, status, last_error FROM okapi_outbox").use { rs ->
                    generateSequence {
                        if (rs.next()) rs.getString(1) to (OutboxStatus.from(rs.getString(2)) to rs.getString(3)) else null
                    }.toMap()
                }
            }
        }
    }

    test("before 005, a latin1 last_error rejects an emoji, and the store keeps the batch with a placeholder error") {
        migrate("legacy_before_005", CHANGESETS_BEFORE_005)
        lastErrorCharset("legacy_before_005") shouldBe "latin1"
        // Control: the raw value really is rejected.
        shouldThrowAny {
            connect("legacy_before_005").use { conn ->
                conn.prepareStatement(
                    "INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, delivery_metadata, last_error) " +
                        "VALUES (UUID(), 'm', '{}', 'charset-test', '{}', ?)",
                ).use {
                    it.setString(1, EMOJI_ERROR)
                    it.executeUpdate()
                }
            }
        }

        val rows = processHealthyAndPoisoned("legacy_before_005")

        rows.getValue("healthy").first shouldBe OutboxStatus.DELIVERED
        rows.getValue("poisoned").first shouldBe OutboxStatus.PENDING
        checkNotNull(rows.getValue("poisoned").second) shouldStartWith "okapi: the database rejected this entry's error message"
    }

    test("005 converts last_error to utf8mb4, which stores the emoji") {
        migrate("legacy_upgraded", CHANGESETS_BEFORE_005)
        lastErrorCharset("legacy_upgraded") shouldBe "latin1"
        migrate("legacy_upgraded")
        execType005("legacy_upgraded") shouldBe "EXECUTED"
        lastErrorCharset("legacy_upgraded") shouldBe "utf8mb4"

        val rows = processHealthyAndPoisoned("legacy_upgraded")

        rows.getValue("healthy").first shouldBe OutboxStatus.DELIVERED
        rows.getValue("poisoned") shouldBe (OutboxStatus.PENDING to EMOJI_ERROR)
    }

    test("005 does nothing where last_error is already utf8mb4") {
        migrate("modern")
        lastErrorCharset("modern") shouldBe "utf8mb4"
        execType005("modern") shouldBe "MARK_RAN"
    }
})
