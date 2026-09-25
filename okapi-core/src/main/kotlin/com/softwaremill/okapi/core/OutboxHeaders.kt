package com.softwaremill.okapi.core

/**
 * Header names okapi attaches to every delivered message, independent of transport.
 *
 * Defined here rather than in a transport module so that each [MessageDeliverer] emits the same
 * name and the same value format, and so consumers can reference the constant without depending
 * on `okapi-kafka` or `okapi-http`.
 */
object OutboxHeaders {
    /**
     * Carries [OutboxEntry.outboxId] — the canonical UUID string, i.e. `outboxId.raw.toString()` —
     * so consumers can deduplicate redeliveries of the same entry.
     *
     * Set by okapi on every delivery, and set **last**, so it always wins over a header of the same
     * name supplied through `DeliveryInfo`: HTTP replaces the value outright, while Kafka appends
     * (headers there are multi-valued), making okapi's the one `lastHeader` returns.
     *
     * The value is stable across retries of an entry, which is what makes it usable for
     * deduplication. It does not identify a *business* event: two `OutboxPublisher.publish` calls
     * for the same event produce two entries with two different ids.
     */
    const val OUTBOX_ID: String = "x-outbox-id"
}
