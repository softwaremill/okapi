package com.softwaremill.okapi.core

/**
 * Transport abstraction for delivering an [OutboxEntry].
 *
 * [type] must match the [DeliveryInfo.type] of the entries this deliverer handles.
 * [CompositeMessageDeliverer] uses this to route entries to the correct implementation.
 */
interface MessageDeliverer {
    val type: String

    /**
     * Delivers a single entry. MUST NOT throw — transport-level errors surface
     * as [DeliveryResult.RetriableFailure] (transient: network, timeout, interrupt)
     * or [DeliveryResult.PermanentFailure] (won't fix itself: corrupt metadata,
     * missing service, auth, payload too large).
     */
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
     * Implementations MUST NOT abort the batch on individual entry failure;
     * the returned list always has the same size as [entries], in input order,
     * with each entry independently classified as Success / RetriableFailure /
     * PermanentFailure. This method MUST NOT throw — transport-level errors
     * surface as [DeliveryResult.RetriableFailure] or [DeliveryResult.PermanentFailure]
     * on the affected entries.
     */
    fun deliverBatch(entries: List<OutboxEntry>): List<Pair<OutboxEntry, DeliveryResult>> = entries.map { entry -> entry to deliver(entry) }
}
