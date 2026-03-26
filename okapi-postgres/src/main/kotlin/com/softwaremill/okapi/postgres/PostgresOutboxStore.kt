package com.softwaremill.okapi.postgres

import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant

/** PostgreSQL [OutboxStore] implementation using Exposed. */
class PostgresOutboxStore(
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
        return OutboxTable
            .select(OutboxTable.columns)
            .where { OutboxTable.status eq OutboxStatus.PENDING.name }
            .orderBy(OutboxTable.createdAt to SortOrder.ASC)
            .limit(limit)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(mode = ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { it.toOutboxEntry() }
    }

    override fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry = persist(entry)

    override fun removeDeliveredBefore(time: Instant, limit: Int): Int {
        val sql = """
            DELETE FROM ${OutboxTable.tableName} WHERE ${OutboxTable.id.name} IN (
                SELECT ${OutboxTable.id.name} FROM ${OutboxTable.tableName}
                WHERE ${OutboxTable.status.name} = '${OutboxStatus.DELIVERED}'
                AND ${OutboxTable.lastAttempt.name} < ?
                ORDER BY ${OutboxTable.id.name}
                LIMIT ?
                FOR UPDATE SKIP LOCKED
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

    private fun ResultRow.toOutboxEntry(): OutboxEntry = OutboxEntry(
        outboxId = this[OutboxTable.id],
        messageType = this[OutboxTable.messageType],
        payload = this[OutboxTable.payload],
        deliveryType = this[OutboxTable.deliveryType],
        status = OutboxStatus.from(this[OutboxTable.status]),
        createdAt = this[OutboxTable.createdAt],
        updatedAt = this[OutboxTable.updatedAt],
        retries = this[OutboxTable.retries],
        lastAttempt = this[OutboxTable.lastAttempt],
        lastError = this[OutboxTable.lastError],
        deliveryMetadata = this[OutboxTable.deliveryMetadata],
    )
}
