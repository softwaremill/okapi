package com.softwaremill.okapi.mysql

import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.upsert
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

class MysqlOutboxStore(
    private val clock: Clock,
) : OutboxStore {
    override fun persist(entry: OutboxEntry): OutboxEntry {
        OutboxTable.upsert {
            it[id] = entry.outboxId
            it[messageType] = entry.messageType
            it[payload] = entry.payload
            it[deliveryType] = entry.deliveryType
            it[status] = entry.status.name
            it[createdAt] = entry.createdAt
            it[updatedAt] = entry.updatedAt
            it[retries] = entry.retries
            it[lastAttempt] = entry.lastAttempt
            it[lastError] = entry.lastError
            it[deliveryMetadata] = entry.deliveryMetadata
        }
        return entry
    }

    override fun claimPending(limit: Int): List<OutboxEntry> {
        val nativeQuery =
            "SELECT * FROM ${OutboxTable.tableName}" +
                " WHERE ${OutboxTable.status.name} = '${OutboxStatus.PENDING}'" +
                " ORDER BY ${OutboxTable.createdAt.name} ASC" +
                " LIMIT $limit FOR UPDATE SKIP LOCKED"

        return TransactionManager.current().exec(nativeQuery) { rs ->
            generateSequence {
                if (rs.next()) mapFromResultSet(rs) else null
            }.toList()
        } ?: emptyList()
    }

    override fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry = persist(entry)

    override fun removeDeliveredBefore(time: Instant, limit: Int): Int {
        val sql = """
            DELETE FROM ${OutboxTable.tableName} WHERE ${OutboxTable.id.name} IN (
                SELECT ${OutboxTable.id.name} FROM (
                    SELECT ${OutboxTable.id.name} FROM ${OutboxTable.tableName}
                    WHERE ${OutboxTable.status.name} = '${OutboxStatus.DELIVERED}'
                    AND ${OutboxTable.lastAttempt.name} < ?
                    ORDER BY ${OutboxTable.id.name}
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ) AS batch
            )
        """.trimIndent()

        val statement = TransactionManager.current().connection.prepareStatement(sql, false)
        statement.fillParameters(
            listOf(
                OutboxTable.lastAttempt.columnType to time,
                IntegerColumnType() to limit,
            ),
        )
        return statement.executeUpdate()
    }

    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>): Map<OutboxStatus, Instant> {
        val result = statuses.associateWith { clock.instant() }.toMutableMap()
        val minAlias = OutboxTable.createdAt.min().alias("min_created_at")
        OutboxTable
            .select(OutboxTable.status, minAlias)
            .where { OutboxTable.status inList statuses.map { status -> status.name } }
            .groupBy(OutboxTable.status)
            .forEach { row ->
                val s = OutboxStatus.from(row[OutboxTable.status])
                result[s] = requireNotNull(row[minAlias])
            }
        return result
    }

    override fun countByStatuses(): Map<OutboxStatus, Long> {
        val countAlias = OutboxTable.status.count().alias("count")
        val counts =
            OutboxTable
                .select(OutboxTable.status, countAlias)
                .groupBy(OutboxTable.status)
                .associate { row -> OutboxStatus.from(row[OutboxTable.status]) to row[countAlias] }
        return OutboxStatus.entries.associateWith { status -> counts[status] ?: 0L }
    }

    private fun mapFromResultSet(rs: ResultSet): OutboxEntry = OutboxEntry(
        outboxId = OutboxId(UUID.fromString(rs.getString("id"))),
        messageType = rs.getString("message_type"),
        payload = rs.getString("payload"),
        deliveryType = rs.getString("delivery_type"),
        status = OutboxStatus.from(rs.getString("status")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
        retries = rs.getInt("retries"),
        lastAttempt = rs.getTimestamp("last_attempt")?.toInstant(),
        lastError = rs.getString("last_error"),
        deliveryMetadata = rs.getString("delivery_metadata"),
    )
}
