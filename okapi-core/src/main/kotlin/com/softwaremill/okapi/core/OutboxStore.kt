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

    /** Returns the oldest createdAt per status (useful for lag metrics). */
    fun findOldestCreatedAt(statuses: Set<OutboxStatus>): Map<OutboxStatus, Instant>

    /** Returns entry count per status. */
    fun countByStatuses(): Map<OutboxStatus, Long>
}
