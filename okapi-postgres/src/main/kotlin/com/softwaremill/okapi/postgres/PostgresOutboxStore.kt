package com.softwaremill.okapi.postgres

import com.softwaremill.okapi.core.ConnectionProvider
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.RouteAwareOutboxStore
import java.sql.PreparedStatement
import java.sql.ResultSet
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
            conn.prepareStatement(updateSql).use { stmt ->
                entries.forEach { entry ->
                    stmt.prepareForUpdateFrom(entry).addBatch()
                }
                stmt.executeBatch()
            }
        }
        return entries
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
