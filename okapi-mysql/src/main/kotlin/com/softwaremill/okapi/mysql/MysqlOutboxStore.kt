package com.softwaremill.okapi.mysql

import com.softwaremill.okapi.core.ConnectionProvider
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** MySQL [OutboxStore] implementation using plain JDBC. */
class MysqlOutboxStore(
    private val connectionProvider: ConnectionProvider,
    private val clock: Clock = Clock.systemUTC(),
) : OutboxStore {

    override fun persist(entry: OutboxEntry): OutboxEntry {
        val sql = """
            INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, last_attempt, last_error, delivery_metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                updated_at = VALUES(updated_at),
                retries = VALUES(retries),
                last_attempt = VALUES(last_attempt),
                last_error = VALUES(last_error),
                delivery_metadata = VALUES(delivery_metadata)
        """.trimIndent()

        connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, entry.outboxId.raw.toString())
                stmt.setString(2, entry.messageType)
                stmt.setString(3, entry.payload)
                stmt.setString(4, entry.deliveryType)
                stmt.setString(5, entry.status.name)
                stmt.setTimestamp(6, Timestamp.from(entry.createdAt))
                stmt.setTimestamp(7, Timestamp.from(entry.updatedAt))
                stmt.setInt(8, entry.retries)
                if (entry.lastAttempt != null) {
                    stmt.setTimestamp(
                        9,
                        Timestamp.from(entry.lastAttempt),
                    )
                } else {
                    stmt.setNull(9, java.sql.Types.TIMESTAMP)
                }
                if (entry.lastError != null) stmt.setString(10, entry.lastError) else stmt.setNull(10, java.sql.Types.VARCHAR)
                stmt.setString(11, entry.deliveryMetadata)
                stmt.executeUpdate()
            }
        }
        return entry
    }

    override fun claimPending(limit: Int): List<OutboxEntry> {
        // FORCE INDEX ensures InnoDB walks the (status, created_at) index so
        // that FOR UPDATE SKIP LOCKED only row-locks the rows actually returned
        // by LIMIT, rather than every row matching the WHERE clause.
        val sql = """
            SELECT * FROM okapi_outbox
            FORCE INDEX (idx_okapi_outbox_status_created_at)
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

    override fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry = persist(entry)

    override fun removeDeliveredBefore(time: Instant, limit: Int): Int {
        val sql = """
            DELETE FROM okapi_outbox WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM okapi_outbox
                    WHERE status = ?
                    AND last_attempt < ?
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ) AS batch
            )
        """.trimIndent()

        return connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, OutboxStatus.DELIVERED.name)
                stmt.setTimestamp(2, Timestamp.from(time))
                stmt.setInt(3, limit)
                stmt.executeUpdate()
            }
        }
    }

    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>): Map<OutboxStatus, Instant> {
        val result = statuses.associateWith { clock.instant() }.toMutableMap()
        val placeholders = statuses.joinToString(",") { "?" }
        val sql = "SELECT status, MIN(created_at) AS min_created_at FROM okapi_outbox WHERE status IN ($placeholders) GROUP BY status"

        connectionProvider.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                statuses.forEachIndexed { i, s -> stmt.setString(i + 1, s.name) }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val s = OutboxStatus.from(rs.getString("status"))
                        result[s] = rs.getTimestamp("min_created_at").toInstant()
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

    private fun ResultSet.toOutboxEntry(): OutboxEntry = OutboxEntry(
        outboxId = OutboxId(UUID.fromString(getString("id"))),
        messageType = getString("message_type"),
        payload = getString("payload"),
        deliveryType = getString("delivery_type"),
        status = OutboxStatus.from(getString("status")),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        retries = getInt("retries"),
        lastAttempt = getTimestamp("last_attempt")?.toInstant(),
        lastError = getString("last_error"),
        deliveryMetadata = getString("delivery_metadata"),
    )
}
