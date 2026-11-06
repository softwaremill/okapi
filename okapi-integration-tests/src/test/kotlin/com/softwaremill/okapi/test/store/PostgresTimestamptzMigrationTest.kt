package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.JdbcConnectionProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.Properties
import java.util.TimeZone

private const val CHANGELOG = "com/softwaremill/okapi/db/postgres/changelog.xml"

private const val INSERT_1X = """
    INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, last_attempt, delivery_metadata)
    VALUES (?::uuid, 'm', '{}', 'http', 'PENDING', ?, ?, 1, ?, '{}'::jsonb)
"""

/**
 * Upgrade path for issue #135: before changeset 003, pgjdbc stored a `java.sql.Timestamp` as the
 * JVM zone's wall-clock time in TIMESTAMP WITHOUT TIME ZONE. Run inside that same JVM, 003 must
 * turn those values back into the instants that were written, because pgjdbc sets the session
 * TimeZone to the JVM default zone and the type change reads values in the session zone.
 *
 * Each test migrates its own database, so they do not depend on each other or on run order.
 */
class PostgresTimestamptzMigrationTest : FunSpec({
    val container = PostgreSQLContainer<Nothing>("postgres:16")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    /** Creates an empty database [name] and returns its JDBC URL. */
    fun freshDatabase(name: String): String {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return container.jdbcUrl.replaceAfterLast('/', name)
    }

    fun connect(url: String, extra: Properties.() -> Unit = {}): Connection = DriverManager.getConnection(
        url,
        Properties().apply {
            setProperty("user", container.username)
            setProperty("password", container.password)
            extra()
        },
    )

    /** Runs the first [changes] changesets (all of them when null) on a connection of its own. */
    fun migrate(url: String, changes: Int? = null) {
        connect(url).use { conn ->
            Liquibase(
                CHANGELOG,
                ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(conn)),
            )
                .use { if (changes != null) it.update(changes, "") else it.update("") }
        }
    }

    fun store(url: String): Pair<JdbcConnectionProvider, PostgresOutboxStore> {
        val jdbc = JdbcConnectionProvider(
            PGSimpleDataSource().apply {
                setURL(url)
                user = container.username
                password = container.password
            },
        )
        return jdbc to PostgresOutboxStore(jdbc)
    }

    fun <T> inJvmZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        return try {
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    test("changeset 003 keeps the instants written by a non-UTC JVM") {
        val url = freshDatabase("upgrade_path")
        val createdAt = Instant.parse("2024-10-27T01:30:00.123456Z")
        val lastAttempt = Instant.parse("2024-06-01T12:00:00Z")

        inJvmZone("Asia/Tokyo") {
            migrate(url, changes = 2)
            // Exactly how 1.x PostgresOutboxStore wrote these columns.
            connect(url).use { conn ->
                conn.prepareStatement(INSERT_1X.trimIndent()).use { stmt ->
                    stmt.setString(1, "00000000-0000-0000-0000-000000000001")
                    stmt.setTimestamp(2, Timestamp.from(createdAt))
                    stmt.setTimestamp(3, Timestamp.from(createdAt))
                    stmt.setTimestamp(4, Timestamp.from(lastAttempt))
                    stmt.executeUpdate()
                }
            }
            migrate(url)
        }

        connect(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT data_type FROM information_schema.columns WHERE table_name = 'okapi_outbox' AND column_name = 'created_at'",
                ).use { rs ->
                    rs.next()
                    rs.getString("data_type") shouldBe "timestamp with time zone"
                }
            }
        }
        val (jdbc, store) = store(url)
        // The route-aware claim, which only returns rows whose next_attempt_at (backfilled by 004) is due.
        val entry = jdbc.withTransaction { store.claimPending(setOf("http"), 10, Instant.now()) }.single()
        entry.nextAttemptAt shouldBe createdAt
        entry.createdAt shouldBe createdAt
        entry.updatedAt shouldBe createdAt
        entry.lastAttempt shouldBe lastAttempt
    }

    test("a 1.x connection whose statements were server-prepared before 003 still writes and reads correct instants") {
        val url = freshDatabase("rolling_deploy")
        val before = Instant.parse("2025-01-15T08:00:00.5Z")
        val after = Instant.parse("2025-01-15T09:00:00.25Z")

        inJvmZone("America/New_York") {
            migrate(url, changes = 2)
            // prepareThreshold=1: pgjdbc server-prepares on first use, as a long-lived pooled 1.x
            // connection will have done long before the migration.
            connect(url) { setProperty("prepareThreshold", "1") }.use { conn ->
                conn.prepareStatement(INSERT_1X.trimIndent()).use { insert ->
                    fun write(id: String, at: Instant) {
                        insert.setString(1, id)
                        insert.setTimestamp(2, Timestamp.from(at))
                        insert.setTimestamp(3, Timestamp.from(at))
                        insert.setTimestamp(4, Timestamp.from(at))
                        insert.executeUpdate()
                    }
                    write("00000000-0000-0000-0000-000000000002", before)
                    migrate(url)
                    // Same prepared statement, now against TIMESTAMPTZ columns.
                    write("00000000-0000-0000-0000-000000000003", after)
                }
                // 1.x read path: getTimestamp on a statement prepared after the migration.
                conn.prepareStatement("SELECT created_at FROM okapi_outbox ORDER BY created_at").use { select ->
                    select.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getTimestamp("created_at").toInstant() else null }.toList()
                    } shouldBe listOf(before, after)
                }
            }
        }

        val (jdbc, store) = store(url)
        // `after` was inserted by 1.x after 004, so only the column DEFAULT made it claimable.
        jdbc.withTransaction { store.claimPending(setOf("http"), 10, Instant.now().plusSeconds(5)) }
            .map { it.createdAt }.sorted() shouldBe listOf(before, after)
    }
})
