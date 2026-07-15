package com.softwaremill.okapi.core

import java.time.Instant

interface OutboxStore {
    /** Persists a new outbox entry (publish). */
    fun persist(entry: OutboxEntry): OutboxEntry

    /** Claims up to [limit] PENDING entries with exclusive access. Locking strategy depends on the adapter. */
    fun claimPending(limit: Int): List<OutboxEntry>

    /** Updates an entry after a delivery attempt (status change, retries, lastError). */
    fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry

    /**
     * Removes up to [limit] DELIVERED entries older than [time].
     * @param limit maximum number of entries to delete; must be positive
     * @return the number of entries actually deleted, always in `[0, limit]`
     */
    fun removeDeliveredBefore(time: Instant, limit: Int): Int

    /**
     * For each of the given [statuses] that has at least one entry, maps that status to the
     * [OutboxEntry.createdAt] of its oldest entry (the minimum `createdAt` among entries in
     * that status). Statuses with no entries are omitted from the result -- callers rely on
     * absence to mean "no backlog" (e.g. the lag gauge reports 0 for an omitted status).
     * Useful for lag metrics.
     */
    fun findOldestCreatedAt(statuses: Set<OutboxStatus>): Map<OutboxStatus, Instant>

    /** Returns entry count per status. */
    fun countByStatuses(): Map<OutboxStatus, Long>

    /**
     * Updates a batch of entries after a delivery attempt in a single roundtrip where
     * possible. Default loops over [updateAfterProcessing] one at a time; storage modules
     * override this with a single JDBC `executeBatch()` call.
     */
    fun updateAfterProcessingBatch(entries: List<OutboxEntry>): List<OutboxEntry> = entries.map { updateAfterProcessing(it) }
}
