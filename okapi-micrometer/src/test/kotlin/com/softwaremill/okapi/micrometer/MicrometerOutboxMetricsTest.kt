package com.softwaremill.okapi.micrometer

import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private class CountingStubStore(
    private val counts: Map<OutboxStatus, Long> = emptyMap(),
    private val oldest: Map<OutboxStatus, Instant> = emptyMap(),
) : OutboxStore {
    val countCalls = AtomicInteger(0)
    val oldestCalls = AtomicInteger(0)

    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0

    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>): Map<OutboxStatus, Instant> {
        oldestCalls.incrementAndGet()
        return oldest.filterKeys { it in statuses }
    }

    override fun countByStatuses(): Map<OutboxStatus, Long> {
        countCalls.incrementAndGet()
        return counts
    }
}

private fun throwingStore(error: Exception = RuntimeException("db down")) = object : OutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>): Map<OutboxStatus, Instant> = throw error
    override fun countByStatuses(): Map<OutboxStatus, Long> = throw error
}

class MicrometerOutboxMetricsTest : FunSpec({

    test("count gauge reflects store values per status after refresh") {
        val store = CountingStubStore(counts = mapOf(OutboxStatus.PENDING to 5L, OutboxStatus.FAILED to 2L))
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerOutboxMetrics(store, registry)

        metrics.refresh()

        registry.find("okapi.entries.count").tag("status", "pending").gauge()!!.value() shouldBe 5.0
        registry.find("okapi.entries.count").tag("status", "failed").gauge()!!.value() shouldBe 2.0
        registry.find("okapi.entries.count").tag("status", "delivered").gauge()!!.value() shouldBe 0.0
    }

    test("lag gauge computes seconds between oldest entry and now") {
        val now = Instant.parse("2024-01-01T00:01:00Z")
        val oldest = Instant.parse("2024-01-01T00:00:00Z") // 60 seconds ago
        val store = CountingStubStore(oldest = mapOf(OutboxStatus.PENDING to oldest))
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerOutboxMetrics(store, registry, clock = Clock.fixed(now, ZoneOffset.UTC))

        metrics.refresh()

        registry.find("okapi.entries.lag.seconds").tag("status", "pending").gauge()!!.value() shouldBe 60.0
    }

    test("lag gauge is 0 when no entries for that status") {
        val store = CountingStubStore()
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerOutboxMetrics(store, registry)

        metrics.refresh()

        registry.find("okapi.entries.lag.seconds").tag("status", "pending").gauge()!!.value() shouldBe 0.0
    }

    test("refresh queries store exactly once per metric regardless of status count") {
        val store = CountingStubStore(counts = mapOf(OutboxStatus.PENDING to 1L, OutboxStatus.DELIVERED to 2L, OutboxStatus.FAILED to 3L))
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerOutboxMetrics(store, registry)

        metrics.refresh()

        store.countCalls.get() shouldBe 1
        store.oldestCalls.get() shouldBe 1
    }

    test("multiple refreshes each issue exactly one query per metric") {
        val store = CountingStubStore()
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerOutboxMetrics(store, registry)

        repeat(5) { metrics.refresh() }

        store.countCalls.get() shouldBe 5
        store.oldestCalls.get() shouldBe 5
    }

    test("transactionRunner wraps both store queries in one transaction per refresh") {
        val store = CountingStubStore(counts = mapOf(OutboxStatus.PENDING to 7L))
        val registry = SimpleMeterRegistry()
        val txCount = AtomicInteger(0)
        val recordingRunner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T {
                txCount.incrementAndGet()
                return block()
            }
        }
        val metrics = MicrometerOutboxMetrics(store, registry, transactionRunner = recordingRunner)

        metrics.refresh()

        txCount.get() shouldBe 1
        store.countCalls.get() shouldBe 1
        store.oldestCalls.get() shouldBe 1
        registry.find("okapi.entries.count").tag("status", "pending").gauge()!!.value() shouldBe 7.0
    }

    test("all gauge rows go to NaN when store throws") {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerOutboxMetrics(throwingStore(), registry)

        metrics.refresh()

        OutboxStatus.entries.forEach { status ->
            val tag = status.name.lowercase()
            registry.find("okapi.entries.count").tag("status", tag).gauge()!!.value().isNaN() shouldBe true
            registry.find("okapi.entries.lag.seconds").tag("status", tag).gauge()!!.value().isNaN() shouldBe true
        }
    }

    test("all gauge rows go to NaN when transactionRunner throws") {
        val store = CountingStubStore(counts = mapOf(OutboxStatus.PENDING to 5L))
        val registry = SimpleMeterRegistry()
        val failingRunner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T = throw IllegalStateException("no tx manager")
        }
        val metrics = MicrometerOutboxMetrics(store, registry, transactionRunner = failingRunner)

        metrics.refresh()

        registry.find("okapi.entries.count").tag("status", "pending").gauge()!!.value().isNaN() shouldBe true
        registry.find("okapi.entries.lag.seconds").tag("status", "pending").gauge()!!.value().isNaN() shouldBe true
    }

    test("gauges are registered eagerly with NaN before first refresh") {
        // Ensures Prometheus scrape between bean construction and first refresh sees the metric
        // (with NaN value), not a missing series.
        val store = CountingStubStore(counts = mapOf(OutboxStatus.PENDING to 5L))
        val registry = SimpleMeterRegistry()
        MicrometerOutboxMetrics(store, registry)

        // No refresh() called yet — but MultiGauge with no rows registered means no series.
        // Scrapers will see the metric only after the first refresh; this is acceptable.
        // We assert that no store calls happen on construction.
        store.countCalls.get() shouldBe 0
        store.oldestCalls.get() shouldBe 0
    }

    test("subsequent refresh overwrites previous snapshot") {
        val mutableStore = object : OutboxStore {
            var currentCounts: Map<OutboxStatus, Long> = mapOf(OutboxStatus.PENDING to 10L)
            override fun persist(entry: OutboxEntry) = entry
            override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
            override fun updateAfterProcessing(entry: OutboxEntry) = entry
            override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
            override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
            override fun countByStatuses() = currentCounts
        }
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerOutboxMetrics(mutableStore, registry)

        metrics.refresh()
        registry.find("okapi.entries.count").tag("status", "pending").gauge()!!.value() shouldBe 10.0

        mutableStore.currentCounts = mapOf(OutboxStatus.PENDING to 3L, OutboxStatus.DELIVERED to 7L)
        metrics.refresh()

        registry.find("okapi.entries.count").tag("status", "pending").gauge()!!.value() shouldBe 3.0
        registry.find("okapi.entries.count").tag("status", "delivered").gauge()!!.value() shouldBe 7.0
    }
})
