package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.JdbcConnectionProvider
import com.softwaremill.okapi.test.support.pgDataSourceOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.TimeZone

private const val CHANGELOG = "com/softwaremill/okapi/db/postgres/changelog.xml"

/**
 * Upgrade path for issue #135: before changeset 003, pgjdbc stored a `java.sql.Timestamp` as the
 * JVM zone's wall-clock time in TIMESTAMP WITHOUT TIME ZONE. Run inside that same JVM, 003 must
 * turn those values back into the instants that were written, because pgjdbc sets the session
 * TimeZone to the JVM default zone and the type change reads values in the session zone.
 */
class PostgresTimestamptzMigrationTest : FunSpec({
    val container = PostgreSQLContainer<Nothing>("postgres:16")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    test("changeset 003 keeps the instants written by a non-UTC JVM") {
        val createdAt = Instant.parse("2024-10-27T01:30:00.123456Z")
        val lastAttempt = Instant.parse("2024-06-01T12:00:00Z")
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                val liquibase =
                    Liquibase(
                        CHANGELOG,
                        ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(conn)),
                    )
                liquibase.update(2, "")

                // Exactly how 1.x PostgresOutboxStore wrote these columns.
                conn.prepareStatement(
                    """
                    INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, last_attempt, delivery_metadata)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'm', '{}', 'http', 'PENDING', ?, ?, 1, ?, '{}'::jsonb)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(createdAt))
                    stmt.setTimestamp(2, Timestamp.from(createdAt))
                    stmt.setTimestamp(3, Timestamp.from(lastAttempt))
                    stmt.executeUpdate()
                }

                liquibase.update("")

                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT data_type FROM information_schema.columns WHERE table_name = 'okapi_outbox' AND column_name = 'created_at'",
                    ).use { rs ->
                        rs.next()
                        rs.getString("data_type") shouldBe "timestamp with time zone"
                    }
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }

        val jdbc = JdbcConnectionProvider(pgDataSourceOf(container))
        val entry = jdbc.withTransaction { PostgresOutboxStore(jdbc).claimPending(10) }.single()
        entry.createdAt shouldBe createdAt
        entry.updatedAt shouldBe createdAt
        entry.lastAttempt shouldBe lastAttempt
    }

    test("a 1.x instance on a non-UTC JVM still writes correct instants after changeset 003") {
        val createdAt = Instant.parse("2025-01-15T08:00:00.5Z")
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                // Exactly how 1.x PostgresOutboxStore wrote these columns.
                conn.prepareStatement(
                    """
                    INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, delivery_metadata)
                    VALUES ('00000000-0000-0000-0000-000000000002', 'm', '{}', 'kafka', 'PENDING', ?, ?, 0, '{}'::jsonb)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(createdAt))
                    stmt.setTimestamp(2, Timestamp.from(createdAt))
                    stmt.executeUpdate()
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }

        val jdbc = JdbcConnectionProvider(pgDataSourceOf(container))
        jdbc.withTransaction { PostgresOutboxStore(jdbc).claimPending(setOf("kafka"), 10) }.single().createdAt shouldBe createdAt
    }
})
