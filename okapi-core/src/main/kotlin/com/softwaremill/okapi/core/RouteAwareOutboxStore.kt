package com.softwaremill.okapi.core

/**
 * Claims only entries for a delivery type handled by the current worker.
 * The returned rows must be locked until the processing transaction completes.
 * Implementations should return at most the requested number of rows ordered by creation time and ID.
 * The processor rejects results with another [OutboxEntry.deliveryType] or more than requested.
 */
interface RouteAwareOutboxStore : OutboxStore {
    fun claimPending(deliveryType: String, limit: Int): List<OutboxEntry>
}
