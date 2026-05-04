package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Orchestrates a single processing cycle: claims pending entries from [OutboxStore],
 * delegates batch delivery to [OutboxEntryProcessor], and persists each result.
 *
 * An optional [OutboxProcessorListener] is notified after each entry and after the
 * full batch. Exceptions in the listener are caught and logged — they never break
 * processing. Transaction management is the caller's responsibility.
 *
 * In the batch processing path, the per-entry `duration` reported in
 * [OutboxProcessingEvent] reflects the **wall-clock duration of the whole batch**
 * (because transports may overlap their per-entry I/O internally — e.g. Kafka's
 * fire-flush-await — making per-entry timing meaningless). Use
 * [OutboxProcessorListener.onBatchProcessed] when you need batch-level timing.
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val entryProcessor: OutboxEntryProcessor,
    private val listener: OutboxProcessorListener? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Claims up to [limit] pending entries, processes them as a batch, and persists
     * each result. Returns the number of entries processed (0 if the store had nothing).
     */
    @JvmOverloads
    fun processNext(limit: Int = 10): Int {
        val batchStart = clock.instant()
        val claimed = store.claimPending(limit)
        if (claimed.isEmpty()) {
            notifyBatch(0, Duration.between(batchStart, clock.instant()))
            return 0
        }

        val processed = entryProcessor.processBatch(claimed)
        val batchDuration = Duration.between(batchStart, clock.instant())

        processed.forEach { updated ->
            store.updateAfterProcessing(updated)
            notifyEntry(updated, batchDuration)
        }
        notifyBatch(processed.size, Duration.between(batchStart, clock.instant()))
        return processed.size
    }

    private fun notifyEntry(updated: OutboxEntry, duration: Duration) {
        if (listener == null) return
        try {
            val event = when (updated.status) {
                OutboxStatus.DELIVERED -> OutboxProcessingEvent.Delivered(updated, duration)
                OutboxStatus.PENDING -> OutboxProcessingEvent.RetryScheduled(updated, duration, updated.lastError ?: "")
                OutboxStatus.FAILED -> OutboxProcessingEvent.Failed(updated, duration, updated.lastError ?: "")
            }
            listener.onEntryProcessed(event)
        } catch (e: Exception) {
            logger.warn("OutboxProcessorListener.onEntryProcessed failed", e)
        }
    }

    private fun notifyBatch(count: Int, duration: Duration) {
        if (listener == null) return
        try {
            listener.onBatchProcessed(count, duration)
        } catch (e: Exception) {
            logger.warn("OutboxProcessorListener.onBatchProcessed failed", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxProcessor::class.java)
    }
}
