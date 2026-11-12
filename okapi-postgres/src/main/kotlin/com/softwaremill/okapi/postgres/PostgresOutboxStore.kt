package com.softwaremill.okapi.postgres

import com.softwaremill.okapi.core.ConnectionProvider
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.RouteAwareOutboxStore
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** PostgreSQL [OutboxStore] implementation using plain JDBC. */
class PostgresOutboxStore(
    private val connectionProvider: ConnectionProvider,
) : RouteAwareOutboxStore {

    override fun persist(entry: OutboxEntry): OutboxEntry {
        connectionProvider.withConnection { conn ->
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setString(1, entry.outboxId.raw.toString())
                stmt.setString(2, entry.messageType)
                stmt.setString(3, entry.payload)
                stmt.setString(4, entry.deliveryType)
                stmt.setString(5, entry.status.name)
                stmt.setInstant(6, entry.createdAt)
                stmt.setInstant(7, entry.updatedAt)
                stmt.setInt(8, entry.retries)
                stmt.setInstant(9, entry.lastAttempt)
                val lastError = entry.lastError
                if (lastError != null) stmt.setString(10, OutboxEntry.sanitizeLastError(lastError)) else stmt.setNull(10, Types.VARCHAR)
                stmt.setString(11, entry.deliveryMetadata)
                stmt.setInstant(12, entry.nextAttemptAt)
                stmt.executeUpdate()
            }
        }
        return entry
    }

    override fun claimPending(limit: Int): List<OutboxEntry> {
        val sql = """
            SELECT * FROM okapi_outbox
            WHERE status = ?
            ORDER BY created_at ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

        return connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, OutboxStatus.PENDING.name)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.toOutboxEntry() else null }.toList()
                }
            }
        }
    }

    override fun claimPending(deliveryTypes: Set<String>, limit: Int, now: Instant): List<OutboxEntry> {
        if (deliveryTypes.isEmpty() || limit <= 0) return emptyList()
        val routes = deliveryTypes.sorted()
        val placeholders = routes.joinToString(",") { "?" }
        // Keep PENDING literal so a generic prepared plan can use the partial index. Walking
        // (delivery_type, next_attempt_at, id) up to `now` skips entries still backing off without
        // scanning them, however many there are.
        val sql = """
            SELECT * FROM okapi_outbox
            WHERE status = 'PENDING' AND delivery_type IN ($placeholders) AND next_attempt_at <= ?
            ORDER BY delivery_type ASC, next_attempt_at ASC, id ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

        return connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                routes.forEachIndexed { index, route -> stmt.setString(index + 1, route) }
                stmt.setInstant(routes.size + 1, now)
                stmt.setInt(routes.size + 2, limit)
                stmt.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.toOutboxEntry() else null }.toList()
                }
            }
        }
    }

    override fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry {
        connectionProvider.withConnection { conn ->
            conn.prepareStatement(updateSql).use { stmt ->
                stmt.prepareForUpdateFrom(entry).executeUpdate()
            }
        }
        return entry
    }

    override fun removeDeliveredBefore(time: Instant, limit: Int): Int {
        val sql = """
            DELETE FROM okapi_outbox WHERE id IN (
                SELECT id FROM okapi_outbox
                WHERE status = ?
                AND last_attempt < ?
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
        """.trimIndent()

        return connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, OutboxStatus.DELIVERED.name)
                stmt.setInstant(2, time)
                stmt.setInt(3, limit)
                stmt.executeUpdate()
            }
        }
    }

    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>): Map<OutboxStatus, Instant> {
        if (statuses.isEmpty()) return emptyMap()

        val result = mutableMapOf<OutboxStatus, Instant>()
        val placeholders = statuses.joinToString(",") { "?" }
        val sql = "SELECT status, MIN(created_at) AS min_created_at FROM okapi_outbox WHERE status IN ($placeholders) GROUP BY status"

        connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                statuses.forEachIndexed { i, s -> stmt.setString(i + 1, s.name) }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val s = OutboxStatus.from(rs.getString("status"))
                        result[s] = checkNotNull(rs.getInstant("min_created_at"))
                    }
                }
            }
        }
        return result
    }

    override fun countByStatuses(): Map<OutboxStatus, Long> {
        val sql = "SELECT status, COUNT(*) AS count FROM okapi_outbox GROUP BY status"
        val counts = mutableMapOf<OutboxStatus, Long>()

        connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        counts[OutboxStatus.from(rs.getString("status"))] = rs.getLong("count")
                    }
                }
            }
        }
        return OutboxStatus.entries.associateWith { counts[it] ?: 0L }
    }

    override fun updateAfterProcessingBatch(entries: List<OutboxEntry>): List<OutboxEntry> {
        if (entries.isEmpty()) return entries
        connectionProvider.withConnection { conn ->
            // A row the database rejects must not roll back the results of the rest of the batch (issue #141),
            // so on failure the rows are written one by one.
            val batchFailure = conn.isolated {
                conn.prepareStatement(updateSql).use { stmt ->
                    entries.forEach { entry ->
                        stmt.prepareForUpdateFrom(entry).addBatch()
                    }
                    stmt.executeBatch()
                }
            } ?: return@withConnection
            logger.debug("Batch update of {} outbox entries failed, writing them one by one: {}", entries.size, batchFailure.toString())
            try {
                entries.forEach { conn.updateRowByRow(it) }
            } catch (e: SQLException) {
                e.addSuppressed(batchFailure)
                throw e
            }
        }
        return entries
    }

    /**
     * Writes [entry] on its own. If the database rejects a value in it (an SQL data exception) and it
     * has a `last_error`, the likely culprit, it is written again with a placeholder error, so that
     * its status and retries are kept. Any other failure propagates.
     */
    private fun Connection.updateRowByRow(entry: OutboxEntry) {
        val rejected = isolated { prepareStatement(updateSql).use { it.prepareForUpdateFrom(entry).executeUpdate() } } ?: return
        val lastError = entry.lastError
        if (lastError == null || !rejected.isRejectedValue()) throw rejected
        val placeholder = entry.copy(lastError = "$REJECTED_LAST_ERROR (SQLState ${rejected.sqlState})")
        isolated { prepareStatement(updateSql).use { it.prepareForUpdateFrom(placeholder).executeUpdate() } }?.let {
            it.addSuppressed(rejected)
            throw it
        }
        logger.warn(
            "The database rejected the last_error of outbox entry {} ({}); stored a placeholder instead. " +
                "First {} characters of the rejected error: {}",
            entry.outboxId.raw,
            rejected.toString(),
            LOGGED_ERROR_LENGTH,
            lastError.take(LOGGED_ERROR_LENGTH),
        )
    }

    /** A value the database refuses to store, as opposed to a transient or transaction-level failure. */
    private fun SQLException.isRejectedValue(): Boolean = sqlState?.startsWith("22") == true

    /**
     * Runs [block] under a savepoint and returns the [SQLException] it threw, after rolling back to the
     * savepoint, or `null`. A Postgres transaction accepts no statement after an error until then. When
     * the database has already rolled back the whole transaction, e.g. a MySQL deadlock victim, the
     * rollback to the savepoint fails and the original exception propagates, so nothing is written
     * outside the claim's transaction. Without a transaction there is nothing to protect, and no
     * savepoint. A connection that refuses savepoints runs [block] as is, and its failure propagates.
     */
    private fun Connection.isolated(block: () -> Unit): SQLException? {
        if (autoCommit) {
            return try {
                block()
                null
            } catch (e: SQLException) {
                e
            }
        }
        val savepoint = try {
            setSavepoint()
        } catch (_: SQLException) {
            block()
            return null
        }
        try {
            block()
        } catch (e: SQLException) {
            try {
                rollback(savepoint)
                // Released, so that the next savepoint is not nested under this one.
                releaseSavepoint(savepoint)
            } catch (rollbackFailure: SQLException) {
                e.addSuppressed(rollbackFailure)
                throw e
            }
            return e
        }
        releaseSavepoint(savepoint)
        return null
    }

    private fun ResultSet.toOutboxEntry(): OutboxEntry = OutboxEntry(
        outboxId = OutboxId(UUID.fromString(getString("id"))),
        messageType = getString("message_type"),
        payload = getString("payload"),
        deliveryType = getString("delivery_type"),
        status = OutboxStatus.from(getString("status")),
        createdAt = checkNotNull(getInstant("created_at")),
        updatedAt = checkNotNull(getInstant("updated_at")),
        retries = getInt("retries"),
        lastAttempt = getInstant("last_attempt"),
        lastError = getString("last_error"),
        deliveryMetadata = getString("delivery_metadata"),
        nextAttemptAt = checkNotNull(getInstant("next_attempt_at")),
    )

    private fun PreparedStatement.prepareForUpdateFrom(entry: OutboxEntry): PreparedStatement {
        setString(1, entry.status.name)
        setInstant(2, entry.updatedAt)
        setInt(3, entry.retries)
        setInstant(4, entry.lastAttempt)
        val lastError = entry.lastError
        if (lastError != null) setString(5, OutboxEntry.sanitizeLastError(lastError)) else setNull(5, Types.VARCHAR)
        setInstant(6, entry.nextAttemptAt)
        setObject(7, entry.outboxId.raw)
        return this
    }

    // The TIMESTAMPTZ columns hold instants (issue #135). pgjdbc binds an OffsetDateTime as timestamptz
    // with its offset, unlike java.sql.Timestamp, which it renders as the JVM zone's wall-clock time --
    // so stored values no longer depend on the JVM or session time zone.
    private fun PreparedStatement.setInstant(index: Int, instant: Instant?) {
        if (instant != null) {
            setObject(index, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
        } else {
            setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
        }
    }

    private fun ResultSet.getInstant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    companion object {
        private val logger = LoggerFactory.getLogger(PostgresOutboxStore::class.java)

        /** Stored instead of a `last_error` the database rejected. ASCII, so that any charset accepts it. */
        private const val REJECTED_LAST_ERROR = "okapi: the database rejected this entry's error message; see the application log"
        private const val LOGGED_ERROR_LENGTH = 2_000

        private val insertSql = """
            INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, last_attempt, last_error, delivery_metadata, next_attempt_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at,
                retries = EXCLUDED.retries,
                last_attempt = EXCLUDED.last_attempt,
                last_error = EXCLUDED.last_error,
                delivery_metadata = EXCLUDED.delivery_metadata,
                next_attempt_at = EXCLUDED.next_attempt_at
        """.trimIndent()

        private val updateSql = """
            UPDATE okapi_outbox
            SET status = ?, updated_at = ?, retries = ?, last_attempt = ?, last_error = ?, next_attempt_at = ?
            WHERE id = ?
        """.trimIndent()
    }
}
