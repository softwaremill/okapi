package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates a single processing cycle: claims pending entries from [OutboxStore],
 * delegates batch delivery to [OutboxEntryProcessor], and persists each result.
 *
 * An optional [OutboxProcessorListener] is notified after each entry and after the
 * full batch. Exceptions in the listener are caught and logged — they never break
 * processing. Transaction management is the caller's responsibility.
 *
 * Listener events describe state the caller's transaction has persisted, so they belong after
 * it commits (issue #137): call [processBatch] inside the transaction and [publishEvents] after
 * it commits, as [OutboxScheduler] does. A commit that fails then emits nothing, and the entries
 * are reported again when they are re-processed. [processNext] does both in one call, so inside a
 * transaction its events precede the commit.
 *
 * In the batch processing path, the per-entry `duration` reported in
 * [OutboxProcessingEvent] reflects the **wall-clock duration of the whole batch**
 * (because transports may overlap their per-entry I/O internally — e.g. Kafka's
 * fire-flush-await — making per-entry timing meaningless). Use
 * [OutboxProcessorListener.onBatchProcessed] when you need batch-level timing.
 */
class OutboxProcessor @JvmOverloads constructor(
    private val store: OutboxStore,
    private val entryProcessor: OutboxEntryProcessor,
    private val listener: OutboxProcessorListener? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val deliveryTypes = entryProcessor.supportedDeliveryTypes.toList()
    private val nextStartingType = AtomicInteger()

    /**
     * Claims up to [limit] pending entries, processes them as a batch, persists each result and
     * notifies the listener at once. Returns the number of entries processed (0 if the store had
     * nothing). Called inside a transaction, the events precede its commit; use [processBatch] and
     * [publishEvents] to emit them only after the commit.
     */
    @JvmOverloads
    fun processNext(limit: Int = 10): Int = processBatch(limit).also { publishEvents(it) }.count

    /**
     * Claims up to [limit] pending entries, processes them as a batch and persists each result,
     * without notifying the listener. Run it inside the processing transaction and pass the result
     * to [publishEvents] once that transaction has committed.
     */
    @JvmOverloads
    fun processBatch(limit: Int = 10): ProcessedBatch {
        val batchStart = clock.instant()
        val routeAwareStore = store as? RouteAwareOutboxStore
            ?: error(
                "OutboxProcessor requires a RouteAwareOutboxStore to avoid claiming unsupported delivery types, " +
                    "got ${store.javaClass.name}. A wrapper or proxy around a store must implement RouteAwareOutboxStore itself.",
            )
        check(deliveryTypes.isNotEmpty()) { "OutboxProcessor requires at least one delivery type" }
        val claimed = if (limit <= 0) {
            emptyList()
        } else {
            val startingType = deliveryTypes[Math.floorMod(nextStartingType.getAndIncrement(), deliveryTypes.size)]
            val first = routeAwareStore.claimPending(startingType, limit, batchStart)
            check(first.size <= limit && first.all { it.deliveryType == startingType }) {
                "RouteAwareOutboxStore returned entries outside the requested type or limit"
            }
            val remainingTypes = deliveryTypes.filterTo(LinkedHashSet()) { it != startingType }
            if (first.size == limit || remainingTypes.isEmpty()) {
                first
            } else {
                val remaining = limit - first.size
                // Stores order this claim by delivery type, so leftover capacity favours types that sort first.
                // Each type is still claimed first once every N ticks, so none is starved.
                val other = routeAwareStore.claimPending(remainingTypes, remaining, batchStart)
                check(other.size <= remaining && other.all { it.deliveryType in remainingTypes }) {
                    "RouteAwareOutboxStore returned entries outside the requested types or limit"
                }
                first + other
            }
        }
        if (claimed.isEmpty()) return ProcessedBatch(emptyList(), batchStart, Duration.ZERO)

        val processed = entryProcessor.processBatch(claimed)
        val deliveryDuration = Duration.between(batchStart, clock.instant())

        store.updateAfterProcessingBatch(processed)
        return ProcessedBatch(processed, batchStart, deliveryDuration)
    }

    /**
     * Notifies the listener of [batch]: [OutboxProcessorListener.onEntryProcessed] for each entry,
     * then [OutboxProcessorListener.onBatchProcessed], whose duration runs from the start of the batch
     * until this call, so it includes the commit when called after one, but not the listener
     * callbacks. A batch's events can be published only once; a second call throws.
     */
    fun publishEvents(batch: ProcessedBatch) {
        check(batch.markPublished()) { "Events of this batch were already published" }
        val batchDuration = Duration.between(batch.startedAt, clock.instant())
        batch.entries.forEach { updated -> notifyEntry(updated, batch.deliveryDuration) }
        notifyBatch(batch.count, batchDuration)
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
