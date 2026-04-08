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

private fun stubStore(counts: Map<OutboxStatus, Long> = emptyMap(), oldest: Map<OutboxStatus, Instant> = emptyMap()) =
    object : OutboxStore {
        override fun persist(entry: OutboxEntry) = entry
        override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
        override fun updateAfterProcessing(entry: OutboxEntry) = entry
        override fun removeDeliveredBefore(time: Instant, limit: Int) = 0

        override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = oldest.filterKeys { it in statuses }

        override fun countByStatuses() = counts
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

    test("count gauge reflects store values per status") {
        val store = stubStore(
            counts = mapOf(OutboxStatus.PENDING to 5L, OutboxStatus.FAILED to 2L),
        )
        val registry = SimpleMeterRegistry()
        MicrometerOutboxMetrics(store, registry)

        val pendingGauge = registry.find("okapi.entries.count").tag("status", "pending").gauge()
        val failedGauge = registry.find("okapi.entries.count").tag("status", "failed").gauge()
        val deliveredGauge = registry.find("okapi.entries.count").tag("status", "delivered").gauge()

        pendingGauge!!.value() shouldBe 5.0
        failedGauge!!.value() shouldBe 2.0
        deliveredGauge!!.value() shouldBe 0.0
    }

    test("lag gauge computes seconds between oldest entry and now") {
        val now = Instant.parse("2024-01-01T00:01:00Z")
        val oldest = Instant.parse("2024-01-01T00:00:00Z") // 60 seconds ago
        val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
        val store = stubStore(oldest = mapOf(OutboxStatus.PENDING to oldest))
        val registry = SimpleMeterRegistry()
        MicrometerOutboxMetrics(store, registry, clock = fixedClock)

        val lagGauge = registry.find("okapi.entries.lag.seconds").tag("status", "pending").gauge()
        lagGauge!!.value() shouldBe 60.0
    }

    test("lag gauge returns 0 when no entries for status") {
        val store = stubStore()
        val registry = SimpleMeterRegistry()
        MicrometerOutboxMetrics(store, registry)

        val lagGauge = registry.find("okapi.entries.lag.seconds").tag("status", "pending").gauge()
        lagGauge!!.value() shouldBe 0.0
    }

    test("transactionRunner wraps gauge queries when provided") {
        val store = stubStore(counts = mapOf(OutboxStatus.PENDING to 7L))
        val registry = SimpleMeterRegistry()
        var wrapCount = 0
        val recordingRunner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T {
                wrapCount++
                return block()
            }
        }
        MicrometerOutboxMetrics(store, registry, transactionRunner = recordingRunner)

        val pendingGauge = registry.find("okapi.entries.count").tag("status", "pending").gauge()
        pendingGauge!!.value() shouldBe 7.0
        wrapCount shouldBe 1
    }

    test("gauge returns NaN and logs when store throws") {
        val registry = SimpleMeterRegistry()
        MicrometerOutboxMetrics(throwingStore(), registry)

        val countGauge = registry.find("okapi.entries.count").tag("status", "pending").gauge()
        val lagGauge = registry.find("okapi.entries.lag.seconds").tag("status", "pending").gauge()

        countGauge!!.value().isNaN() shouldBe true
        lagGauge!!.value().isNaN() shouldBe true
    }

    test("gauge returns NaN when transactionRunner throws") {
        val store = stubStore(counts = mapOf(OutboxStatus.PENDING to 5L))
        val registry = SimpleMeterRegistry()
        val failingRunner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T = throw IllegalStateException("no tx manager")
        }
        MicrometerOutboxMetrics(store, registry, transactionRunner = failingRunner)

        val pendingGauge = registry.find("okapi.entries.count").tag("status", "pending").gauge()
        pendingGauge!!.value().isNaN() shouldBe true
    }
})
