package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Orchestrates a single processing cycle: claims pending entries from [OutboxStore],
 * delegates each to [OutboxEntryProcessor], and persists the result.
 *
 * An optional [OutboxProcessorListener] is notified after each entry and after the
 * full batch. Exceptions in the listener are caught and logged — they never break
 * processing. Transaction management is the caller's responsibility.
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val entryProcessor: OutboxEntryProcessor,
    private val listener: OutboxProcessorListener? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun processNext(limit: Int = 10) {
        val batchStart = clock.instant()
        var count = 0
        store.claimPending(limit).forEach { entry ->
            val entryStart = clock.instant()
            val updated = entryProcessor.process(entry)
            val deliveryDuration = Duration.between(entryStart, clock.instant())
            store.updateAfterProcessing(updated)
            count++
            notifyEntry(updated, deliveryDuration)
        }
        notifyBatch(count, Duration.between(batchStart, clock.instant()))
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
