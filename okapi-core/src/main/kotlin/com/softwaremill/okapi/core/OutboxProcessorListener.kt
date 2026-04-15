package com.softwaremill.okapi.core

import java.time.Duration

/**
 * Callback interface for observing [OutboxProcessor] activity.
 *
 * Default no-op implementations allow consumers to override only the
 * methods they care about. Exceptions thrown by implementations are
 * caught and logged — they never break processing.
 */
interface OutboxProcessorListener {
    /** Called after each entry is processed (delivered, retried, or failed). */
    fun onEntryProcessed(event: OutboxProcessingEvent) {}

    /** Called after a full batch completes (even if empty). */
    fun onBatchProcessed(processedCount: Int, duration: Duration) {}
}
