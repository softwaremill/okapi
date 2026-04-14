package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration.ofMillis
import java.time.Duration.ofMinutes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OutboxSchedulerTest : FunSpec({

    test("tick calls processNext with configured batchSize") {
        var capturedLimit: Int? = null
        val latch = CountDownLatch(1)
        val processor = stubProcessor { limit ->
            capturedLimit = limit
            latch.countDown()
        }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            config = OutboxSchedulerConfig(interval = ofMillis(50), batchSize = 25),
        )

        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        capturedLimit shouldBe 25
    }

    test("exception in tick does not kill scheduler") {
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(2)
        val processor = stubProcessor { _ ->
            val count = callCount.incrementAndGet()
            latch.countDown()
            if (count == 1) throw RuntimeException("db connection lost")
        }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            config = OutboxSchedulerConfig(interval = ofMillis(50)),
        )

        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        callCount.get() shouldBe 2
    }

    test("double start is ignored") {
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val processor = stubProcessor { _ ->
            callCount.incrementAndGet()
            latch.countDown()
        }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            config = OutboxSchedulerConfig(interval = ofMillis(50)),
        )

        scheduler.start()
        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        scheduler.isRunning() shouldBe false
    }

    test("isRunning transitions") {
        val processor = stubProcessor { _ -> }
        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            config = OutboxSchedulerConfig(interval = ofMinutes(1)),
        )

        scheduler.isRunning() shouldBe false
        scheduler.start()
        scheduler.isRunning() shouldBe true
        scheduler.stop()
        scheduler.isRunning() shouldBe false
    }

    test("start after stop throws") {
        val processor = stubProcessor { _ -> }
        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            config = OutboxSchedulerConfig(interval = ofMinutes(1)),
        )

        scheduler.start()
        scheduler.stop()

        shouldThrow<IllegalStateException> {
            scheduler.start()
        }.message shouldBe "OutboxScheduler cannot be restarted after stop()"
    }

    test("transactionRunner wraps tick when provided") {
        val txInvoked = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val processor = stubProcessor { _ -> latch.countDown() }
        val txRunner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T {
                txInvoked.set(true)
                return block()
            }
        }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = txRunner,
            config = OutboxSchedulerConfig(interval = ofMillis(50)),
        )

        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        txInvoked.get() shouldBe true
    }

    test("tick runs without transactionRunner") {
        val latch = CountDownLatch(1)
        val processor = stubProcessor { _ -> latch.countDown() }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = null,
            config = OutboxSchedulerConfig(interval = ofMillis(50)),
        )

        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()
    }
})

private fun stubProcessor(onProcessNext: (Int) -> Unit): OutboxProcessor {
    val store = object : OutboxStore {
        override fun persist(entry: OutboxEntry) = entry
        override fun claimPending(limit: Int): List<OutboxEntry> {
            onProcessNext(limit)
            return emptyList()
        }
        override fun updateAfterProcessing(entry: OutboxEntry) = entry
        override fun removeDeliveredBefore(time: java.time.Instant, limit: Int) = 0
        override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, java.time.Instant>()
        override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
    }
    val entryProcessor = OutboxEntryProcessor(
        deliverer = object : MessageDeliverer {
            override val type = "stub"
            override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
        },
        retryPolicy = RetryPolicy(maxRetries = 3),
        clock = java.time.Clock.systemUTC(),
    )
    return OutboxProcessor(store = store, entryProcessor = entryProcessor)
}
