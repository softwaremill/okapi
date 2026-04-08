package com.softwaremill.okapi.micrometer

import com.softwaremill.okapi.core.OutboxProcessingEvent
import com.softwaremill.okapi.core.OutboxProcessingEvent.Delivered
import com.softwaremill.okapi.core.OutboxProcessingEvent.Failed
import com.softwaremill.okapi.core.OutboxProcessingEvent.Retried
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
 * - `okapi.entries.retried` — counter
 * - `okapi.entries.failed` — counter
 * - `okapi.batch.duration` — timer
 */
class MicrometerOutboxListener(registry: MeterRegistry) : OutboxProcessorListener {
    private val deliveredCounter = Counter.builder("okapi.entries.delivered").register(registry)
    private val retriedCounter = Counter.builder("okapi.entries.retried").register(registry)
    private val failedCounter = Counter.builder("okapi.entries.failed").register(registry)
    private val batchTimer = Timer.builder("okapi.batch.duration").register(registry)

    override fun onEntryProcessed(event: OutboxProcessingEvent) = when (event) {
        is Delivered -> deliveredCounter.increment()
        is Retried -> retriedCounter.increment()
        is Failed -> failedCounter.increment()
    }

    override fun onBatchProcessed(processedCount: Int, duration: Duration) {
        batchTimer.record(duration)
    }
}
