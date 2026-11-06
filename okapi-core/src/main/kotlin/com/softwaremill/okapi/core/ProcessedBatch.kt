package com.softwaremill.okapi.core

import java.time.Duration
import java.time.Instant

/**
 * The outcome of [OutboxProcessor.processBatch]: [entries] in the state just persisted, whose
 * listener events are held back until [OutboxProcessor.publishEvents].
 */
class ProcessedBatch internal constructor(
    val entries: List<OutboxEntry>,
    internal val startedAt: Instant,
    internal val deliveryDuration: Duration,
) {
    /** Number of entries processed (0 if the store had nothing to claim). */
    val count: Int get() = entries.size
}
