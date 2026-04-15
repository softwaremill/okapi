package com.softwaremill.okapi.micrometer

import com.softwaremill.okapi.core.OutboxProcessingEvent
import com.softwaremill.okapi.core.OutboxProcessingEvent.Delivered
import com.softwaremill.okapi.core.OutboxProcessingEvent.Failed
import com.softwaremill.okapi.core.OutboxProcessingEvent.RetryScheduled
import com.softwaremill.okapi.core.OutboxProcessorListener
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * [OutboxProcessorListener] that records delivery outcomes as Micrometer counters
 * and batch duration as a timer.
 *
 * Registered metrics:
 * - `okapi.entries.delivered` — counter
 * - `okapi.entries.retry.scheduled` — counter
 * - `okapi.entries.failed` — counter
 * - `okapi.batch.duration` — timer
 *
 * Note: `processedCount` from [onBatchProcessed] is not recorded as a separate metric;
 * per-entry counters provide sufficient granularity.
 */
class MicrometerOutboxListener(registry: MeterRegistry) : OutboxProcessorListener {
    private val deliveredCounter = Counter.builder("okapi.entries.delivered").register(registry)
    private val retryScheduledCounter = Counter.builder("okapi.entries.retry.scheduled").register(registry)
    private val failedCounter = Counter.builder("okapi.entries.failed").register(registry)
    private val batchTimer = Timer.builder("okapi.batch.duration").register(registry)

    override fun onEntryProcessed(event: OutboxProcessingEvent) = when (event) {
        is Delivered -> deliveredCounter.increment()
        is RetryScheduled -> retryScheduledCounter.increment()
        is Failed -> failedCounter.increment()
    }

    override fun onBatchProcessed(processedCount: Int, duration: Duration) {
        batchTimer.record(duration)
    }
}
