package com.softwaremill.okapi.core

import java.time.Duration

/**
 * Outcome of processing a single [OutboxEntry], emitted by [OutboxProcessor]
 * to [OutboxProcessorListener].
 *
 * Sealed hierarchy enables exhaustive `when` in Kotlin — the compiler warns
 * if a new subtype is added and a consumer does not handle it.
 */
sealed interface OutboxProcessingEvent {
    val entry: OutboxEntry
    val duration: Duration

    data class Delivered(override val entry: OutboxEntry, override val duration: Duration) : OutboxProcessingEvent
    data class RetryScheduled(override val entry: OutboxEntry, override val duration: Duration, val error: String) : OutboxProcessingEvent
    data class Failed(override val entry: OutboxEntry, override val duration: Duration, val error: String) : OutboxProcessingEvent
}
