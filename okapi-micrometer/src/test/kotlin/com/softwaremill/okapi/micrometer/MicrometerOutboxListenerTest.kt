package com.softwaremill.okapi.micrometer

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessingEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant

private val stubDeliveryInfo =
    object : DeliveryInfo {
        override val type = "stub"

        override fun serialize(): String = """{"type":"stub"}"""
    }

private fun stubEntry(): OutboxEntry = OutboxEntry.createPending(OutboxMessage("test.event", "{}"), stubDeliveryInfo, Instant.EPOCH)

class MicrometerOutboxListenerTest : FunSpec({

    test("Delivered event increments delivered counter") {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerOutboxListener(registry)
        val entry = stubEntry().toDelivered(Instant.EPOCH)

        listener.onEntryProcessed(OutboxProcessingEvent.Delivered(entry, Duration.ofMillis(42)))

        registry.counter("okapi.entries.delivered").count() shouldBe 1.0
        registry.counter("okapi.entries.retry_scheduled").count() shouldBe 0.0
        registry.counter("okapi.entries.failed").count() shouldBe 0.0
    }

    test("RetryScheduled event increments retry_scheduled counter") {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerOutboxListener(registry)
        val entry = stubEntry().retry(Instant.EPOCH, "timeout")

        listener.onEntryProcessed(OutboxProcessingEvent.RetryScheduled(entry, Duration.ofMillis(10), "timeout"))

        registry.counter("okapi.entries.retry_scheduled").count() shouldBe 1.0
    }

    test("Failed event increments failed counter") {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerOutboxListener(registry)
        val entry = stubEntry().toFailed(Instant.EPOCH, "bad request")

        listener.onEntryProcessed(OutboxProcessingEvent.Failed(entry, Duration.ofMillis(5), "bad request"))

        registry.counter("okapi.entries.failed").count() shouldBe 1.0
    }

    test("onBatchProcessed records duration in timer") {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerOutboxListener(registry)

        listener.onBatchProcessed(3, Duration.ofMillis(150))

        val timer = registry.timer("okapi.batch.duration")
        timer.count() shouldBe 1
        timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe 150.0
    }
})
