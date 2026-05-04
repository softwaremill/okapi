package com.softwaremill.okapi.core

/**
 * Transport abstraction for delivering an [OutboxEntry].
 *
 * [type] must match the [DeliveryInfo.type] of the entries this deliverer handles.
 * [CompositeMessageDeliverer] uses this to route entries to the correct implementation.
 */
interface MessageDeliverer {
    val type: String

    fun deliver(entry: OutboxEntry): DeliveryResult

    /**
     * Delivers a batch of entries, returning per-entry results in the same order
     * as the input list.
     *
     * The default implementation delegates to [deliver] sequentially and is
     * appropriate for any transport. Implementations whose underlying I/O can
     * be overlapped (e.g. Kafka's internal record batching, parallel HTTP
     * `sendAsync`) should override this method to exploit that.
     *
     * Per-entry result classification (Success / RetriableFailure / PermanentFailure)
     * is preserved — callers receive one [DeliveryResult] per input entry.
     */
    fun deliverBatch(entries: List<OutboxEntry>): List<Pair<OutboxEntry, DeliveryResult>> = entries.map { entry -> entry to deliver(entry) }
}
