package com.softwaremill.okapi.micrometer

import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun newCountingStore(refreshes: AtomicInteger): OutboxStore = object : OutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses(): Map<OutboxStatus, Long> {
        refreshes.incrementAndGet()
        return emptyMap()
    }
}

class OutboxMetricsRefresherTest : FunSpec({

    test("start triggers an immediate refresh and then continues at interval") {
        val refreshes = AtomicInteger(0)
        val store = newCountingStore(refreshes)
        val metrics = MicrometerOutboxMetrics(store, SimpleMeterRegistry())
        val refresher = OutboxMetricsRefresher(metrics, Duration.ofMillis(50))

        refresher.use {
            it.start()
            // wait for at least 3 refreshes (initial + 2 ticks)
            val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            while (refreshes.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            refreshes.get() shouldBeGreaterThanOrEqualTo 3
        }
    }

    test("close stops the executor and prevents further refreshes") {
        val refreshes = AtomicInteger(0)
        val store = newCountingStore(refreshes)
        val metrics = MicrometerOutboxMetrics(store, SimpleMeterRegistry())
        val refresher = OutboxMetricsRefresher(metrics, Duration.ofMillis(50))

        refresher.start()
        // wait for one refresh to confirm the executor is alive
        val latch = CountDownLatch(1)
        Thread {
            while (refreshes.get() < 1) Thread.sleep(5)
            latch.countDown()
        }.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true

        refresher.close()
        val countAtClose = refreshes.get()
        Thread.sleep(200) // well over interval
        // no more increments after close
        refreshes.get() shouldBe countAtClose
    }

    test("start is idempotent — calling twice does not spawn extra executor") {
        val refreshes = AtomicInteger(0)
        val store = newCountingStore(refreshes)
        val metrics = MicrometerOutboxMetrics(store, SimpleMeterRegistry())
        val refresher = OutboxMetricsRefresher(metrics, Duration.ofMillis(50))

        refresher.use {
            it.start()
            it.start() // second call should be a no-op
            Thread.sleep(150)
            // With one executor at 50ms interval, expect ~3 refreshes in 150ms,
            // not ~6 as would happen if two executors ran in parallel.
            refreshes.get() shouldBeGreaterThanOrEqualTo 1
            (refreshes.get() <= 5) shouldBe true
        }
    }

    test("refresher survives metrics.refresh() throwing — keeps ticking") {
        // This test guards against an executor death scenario. The refresher catches
        // exceptions internally so a transient failure doesn't kill the schedule.
        val ticks = AtomicInteger(0)
        val throwingThenWorking = object : OutboxStore {
            override fun persist(entry: OutboxEntry) = entry
            override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
            override fun updateAfterProcessing(entry: OutboxEntry) = entry
            override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
            override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
            override fun countByStatuses(): Map<OutboxStatus, Long> {
                val n = ticks.incrementAndGet()
                if (n == 1) throw RuntimeException("transient failure")
                return emptyMap()
            }
        }
        val metrics = MicrometerOutboxMetrics(throwingThenWorking, SimpleMeterRegistry())
        val refresher = OutboxMetricsRefresher(metrics, Duration.ofMillis(50))

        refresher.use {
            it.start()
            val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            while (ticks.get() < 3 && System.nanoTime() < deadline) Thread.sleep(10)
            ticks.get() shouldBeGreaterThanOrEqualTo 3
        }
    }

    test("constructor rejects non-positive interval") {
        val metrics = MicrometerOutboxMetrics(newCountingStore(AtomicInteger(0)), SimpleMeterRegistry())
        try {
            OutboxMetricsRefresher(metrics, Duration.ZERO)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }
})
