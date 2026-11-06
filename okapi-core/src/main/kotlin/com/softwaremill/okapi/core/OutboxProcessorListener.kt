package com.softwaremill.okapi.core

import java.time.Duration

/**
 * Callback interface for observing [OutboxProcessor] activity.
 *
 * Default no-op implementations allow consumers to override only the
 * methods they care about. Exceptions thrown by implementations are
 * caught and logged — they never break processing.
 *
 * [OutboxProcessor] accepts a single listener. To combine multiple listeners,
 * implement a composite that delegates to each.
 *
 * Under [OutboxScheduler], callbacks run after the processing transaction has committed, outside of
 * it, and not at all for a batch whose commit failed (issue #137). A callback therefore cannot take
 * part in that transaction, and every event describes persisted state. With [OutboxProcessor.processNext]
 * called inside your own transaction, events still precede your commit.
 */
interface OutboxProcessorListener {
    /** Called after each entry is processed (delivered, retried, or failed). */
    fun onEntryProcessed(event: OutboxProcessingEvent) {}

    /** Called after a full batch completes (even if empty). */
    fun onBatchProcessed(processedCount: Int, duration: Duration) {}
}
