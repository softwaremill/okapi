package com.softwaremill.okapi.core

import java.time.Duration

/**
 * Outcome of processing a single [OutboxEntry], emitted by [OutboxProcessor]
 * to [OutboxProcessorListener].
 */
sealed interface OutboxProcessingEvent {
    val entry: OutboxEntry

    /** Wall-clock duration of the delivery attempt, excluding the database update. */
    val duration: Duration

    data class Delivered(override val entry: OutboxEntry, override val duration: Duration) : OutboxProcessingEvent
    data class RetryScheduled(override val entry: OutboxEntry, override val duration: Duration, val error: String) : OutboxProcessingEvent
    data class Failed(override val entry: OutboxEntry, override val duration: Duration, val error: String) : OutboxProcessingEvent
}
