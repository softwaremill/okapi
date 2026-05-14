package com.softwaremill.okapi.core

/**
 * Per-entry result of one [MessageDeliverer.deliverBatch] invocation:
 * the original [entry] paired with the transport's classification of its
 * delivery attempt as [DeliveryResult.Success], [DeliveryResult.RetriableFailure],
 * or [DeliveryResult.PermanentFailure].
 *
 * This is a transient transport-layer report — it is consumed by
 * [OutboxEntryProcessor] in the same batch cycle and never persisted.
 */
data class DeliveryOutcome(
    val entry: OutboxEntry,
    val result: DeliveryResult,
)
