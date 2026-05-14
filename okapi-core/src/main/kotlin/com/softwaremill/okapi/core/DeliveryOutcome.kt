package com.softwaremill.okapi.core

/**
 * Per-entry result of one [MessageDeliverer.deliverBatch] invocation.
 *
 * Transient transport-layer report — consumed by [OutboxEntryProcessor]
 * in the same batch cycle and never persisted.
 */
data class DeliveryOutcome(
    val entry: OutboxEntry,
    val result: DeliveryResult,
)
