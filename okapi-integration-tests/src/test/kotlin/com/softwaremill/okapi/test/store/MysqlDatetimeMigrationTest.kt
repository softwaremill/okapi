package com.softwaremill.okapi.test.store

import com.mysql.cj.jdbc.MysqlDataSource
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.JdbcConnectionProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties

private const val CHANGELOG = "com/softwaremill/okapi/db/mysql/changelog.xml"

/**
 * Upgrade path for issue #138: rows written while the columns were TIMESTAMP must keep their
 * instants when changeset 003 converts them to DATETIME, even though the migrating session runs in
 * another time zone (MODIFY converts through the session zone).
 */
class MysqlDatetimeMigrationTest : FunSpec({
    val container = MySQLContainer<Nothing>("mysql:8.0")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    /** A connection whose session time zone is +05:00, as a non-UTC server or pool would give. */
    fun offsetSessionConnection(): Connection = DriverManager.getConnection(
        container.jdbcUrl,
        Properties().apply {
            setProperty("user", container.username)
            setProperty("password", container.password)
            setProperty("sessionVariables", "time_zone='+05:00'")
        },
    )

    test("changeset 003 keeps existing instants and restores the session time zone") {
        offsetSessionConnection().use { conn ->
            val defaultLockWaitTimeout = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT @@session.lock_wait_timeout").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            val liquibase =
                Liquibase(
                    CHANGELOG,
                    ClassLoaderResourceAccessor(),
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(conn)),
                )
            liquibase.update(2, "")

            // A TIMESTAMP literal is read in the session zone: 17:00 at +05:00 is 12:00Z.
            conn.createStatement().use {
                it.execute(
                    """
                    INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, last_attempt, delivery_metadata)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'm', '{}', 'http', 'PENDING',
                            '2024-06-01 17:00:00.250000', '2024-06-01 17:00:01', 1, '2024-06-01 17:00:02', '{}')
                    """.trimIndent(),
                )
            }

            liquibase.update("")

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT @@session.time_zone AS tz, @@session.lock_wait_timeout AS lwt").use { rs ->
                    rs.next()
                    rs.getString("tz") shouldBe "+05:00"
                    rs.getLong("lwt") shouldBe defaultLockWaitTimeout
                }
                val columns = stmt.executeQuery(
                    """
                    SELECT COLUMN_NAME, DATA_TYPE, DATETIME_PRECISION, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'okapi_outbox'
                      AND COLUMN_NAME IN ('created_at', 'updated_at', 'last_attempt')
                    ORDER BY COLUMN_NAME
                    """.trimIndent(),
                ).use { rs ->
                    generateSequence {
                        if (rs.next()) {
                            listOf(
                                rs.getString("COLUMN_NAME"),
                                rs.getString("DATA_TYPE"),
                                rs.getInt("DATETIME_PRECISION").toString(),
                                rs.getString("IS_NULLABLE"),
                                rs.getString("COLUMN_DEFAULT") ?: "null",
                                rs.getString("EXTRA"),
                            ).joinToString("|")
                        } else {
                            null
                        }
                    }.toList()
                }
                columns shouldBe listOf(
                    "created_at|datetime|6|NO|null|",
                    "last_attempt|datetime|6|YES|null|",
                    "updated_at|datetime|6|NO|null|",
                )
            }
        }

        val jdbc = JdbcConnectionProvider(
            MysqlDataSource().apply {
                setURL(container.jdbcUrl)
                user = container.username
                setPassword(container.password)
            },
        )
        val entry = jdbc.withTransaction { MysqlOutboxStore(jdbc).claimPending(10) }.single()
        entry.createdAt shouldBe Instant.parse("2024-06-01T12:00:00.25Z")
        entry.updatedAt shouldBe Instant.parse("2024-06-01T12:00:01Z")
        entry.lastAttempt shouldBe Instant.parse("2024-06-01T12:00:02Z")
        entry.status shouldBe OutboxStatus.PENDING
    }
})
