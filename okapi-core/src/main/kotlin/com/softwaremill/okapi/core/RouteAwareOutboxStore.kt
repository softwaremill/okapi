package com.softwaremill.okapi.core

import java.time.Instant

/**
 * Claims only entries for delivery types handled by the current worker, and only those that are due.
 * The returned rows must be locked until the processing transaction completes.
 * Implementations should return at most the requested number of `PENDING` rows whose
 * [OutboxEntry.nextAttemptAt] is at or before `now`, ordered by delivery type, next attempt time and ID.
 * The processor rejects results with another [OutboxEntry.deliveryType] or more than requested.
 */
interface RouteAwareOutboxStore : OutboxStore {
    fun claimPending(deliveryTypes: Set<String>, limit: Int, now: Instant): List<OutboxEntry>

    fun claimPending(deliveryType: String, limit: Int, now: Instant): List<OutboxEntry> = claimPending(setOf(deliveryType), limit, now)
}
