package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration.ofDays
import java.time.Duration.ofMillis
import java.time.Duration.ofMinutes
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val fixedNow = Instant.parse("2025-03-20T12:00:00Z")
private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

class OutboxPurgerTest : FunSpec({

    test("tick removes entries older than retention duration with correct batch size") {
        var capturedCutoff: Instant? = null
        var capturedLimit: Int? = null
        val latch = CountDownLatch(1)
        val store = stubStore(onRemove = { time, limit ->
            capturedCutoff = time
            capturedLimit = limit
            latch.countDown()
            0
        })

        val purger = OutboxPurger(
            outboxStore = store,
            config = OutboxPurgerConfig(
                retention = ofDays(7),
                interval = ofMillis(50),
                batchSize = 100,
            ),
            clock = fixedClock,
        )

        purger.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        purger.stop()

        capturedCutoff shouldBe fixedNow.minus(ofDays(7))
        capturedLimit shouldBe 100
    }

    test("batch loop stops when deleted < batchSize") {
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val store = stubStore(onRemove = { _, _ ->
            val count = callCount.incrementAndGet()
            if (count == 1) {
                100
            } else {
                latch.countDown()
                42
            }
        })

        val purger = OutboxPurger(
            outboxStore = store,
            config = OutboxPurgerConfig(interval = ofMillis(50), batchSize = 100),
            clock = fixedClock,
        )
        purger.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        purger.stop()

        callCount.get() shouldBe 2
    }

    test("batch loop respects MAX_BATCHES_PER_TICK") {
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val store = stubStore(onRemove = { _, _ ->
            val count = callCount.incrementAndGet()
            if (count >= 10) latch.countDown()
            100
        })

        val purger = OutboxPurger(
            outboxStore = store,
            config = OutboxPurgerConfig(interval = ofMillis(50), batchSize = 100),
            clock = fixedClock,
        )
        purger.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        purger.stop()

        callCount.get() shouldBe 10
    }

    test("exception in tick does not kill scheduler") {
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(2)
        val store = stubStore(onRemove = { _, _ ->
            val count = callCount.incrementAndGet()
            latch.countDown()
            if (count == 1) throw RuntimeException("db connection lost")
            0
        })

        val purger = OutboxPurger(
            outboxStore = store,
            config = OutboxPurgerConfig(interval = ofMillis(50), batchSize = 100),
            clock = fixedClock,
        )
        purger.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        purger.stop()

        callCount.get() shouldBe 2
    }

    test("double start is ignored") {
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val store = stubStore(onRemove = { _, _ ->
            callCount.incrementAndGet()
            latch.countDown()
            0
        })

        val purger = OutboxPurger(
            outboxStore = store,
            config = OutboxPurgerConfig(interval = ofMillis(50), batchSize = 100),
            clock = fixedClock,
        )
        purger.start()
        purger.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        purger.stop()

        purger.isRunning() shouldBe false
    }

    test("isRunning transitions") {
        val store = stubStore(onRemove = { _, _ -> 0 })
        val purger = OutboxPurger(
            outboxStore = store,
            config = OutboxPurgerConfig(interval = ofMinutes(1), batchSize = 100),
            clock = fixedClock,
        )

        purger.isRunning() shouldBe false
        purger.start()
        purger.isRunning() shouldBe true
        purger.stop()
        purger.isRunning() shouldBe false
    }

    test("start after stop throws") {
        val purger = OutboxPurger(
            outboxStore = stubStore(),
            config = OutboxPurgerConfig(interval = ofMinutes(1), batchSize = 100),
            clock = fixedClock,
        )

        purger.start()
        purger.stop()

        shouldThrow<IllegalStateException> {
            purger.start()
        }.message shouldBe "OutboxPurger cannot be restarted after stop()"
    }
})

private fun stubStore(onRemove: (Instant, Int) -> Int = { _, _ -> 0 }) = object : OutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int): Int = onRemove(time, limit)
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}
