package com.softwaremill.okapi.core

import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The outcome of [OutboxProcessor.processBatch]: [entries] in the state just persisted, whose
 * listener events are held back until [OutboxProcessor.publishEvents]. Only [OutboxProcessor]
 * creates one; its constructor and timing fields are internal to okapi-core.
 */
class ProcessedBatch internal constructor(
    entries: List<OutboxEntry>,
    internal val startedAt: Instant,
    internal val deliveryDuration: Duration,
) {
    /** The processed entries, in the state persisted for them. Read-only. */
    val entries: List<OutboxEntry> = Collections.unmodifiableList(entries.toList())

    /** Number of entries processed (0 if the store had nothing to claim). */
    val count: Int get() = entries.size

    private val published = AtomicBoolean(false)

    /** Returns `true` the first time only. */
    internal fun markPublished(): Boolean = published.compareAndSet(false, true)

    override fun toString(): String = "ProcessedBatch(count=$count, startedAt=$startedAt, deliveryDuration=$deliveryDuration)"
}
