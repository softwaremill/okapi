package com.softwaremill.okapi.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
            config = OutboxPurgerConfig(interval = ofMillis(50), batchSize = 100),
            clock = fixedClock,
        )
        purger.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        purger.stop()

        callCount.get() shouldBe 2
    }

    test("error log preserves partial batch progress on mid-loop failure") {
        // Batches before the failing one are not rolled back, so the error log must report how
        // many completed -- otherwise an operator paged on "Outbox purge failed" cannot tell
        // whether 0 or 9000 entries were purged before the failure without inspecting the
        // database directly. (This stub takes the no-transactionRunner path; the counts are
        // reported identically regardless of whether a transactionRunner wraps each batch.)
        //
        // Asserts on the logger's typed arguments (Int batches, Int totalDeleted), not on the
        // formatted message text -- decoupling the regression check from log wording changes.
        val callCount = AtomicInteger(0)
        val errorLogged = CountDownLatch(1)
        val store = stubStore(onRemove = { _, _ ->
            val count = callCount.incrementAndGet()
            if (count == 3) throw RuntimeException("simulated db failure")
            100
        })

        val purgerLogger = LoggerFactory.getLogger(OutboxPurger::class.java) as Logger
        val appender = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                super.append(event)
                if (event.level == Level.ERROR) errorLogged.countDown()
            }
        }.apply { start() }
        purgerLogger.addAppender(appender)

        try {
            val purger = OutboxPurger(
                outboxStore = store,
                transactionRunner = noOpTransactionRunner(),
                config = OutboxPurgerConfig(interval = ofMillis(50), batchSize = 100),
                clock = fixedClock,
            )
            purger.start()
            errorLogged.await(2, TimeUnit.SECONDS) shouldBe true
            purger.stop()

            val errorEvent = appender.list.single { it.level == Level.ERROR }
            errorEvent.argumentArray.toList() shouldBe listOf(2, 200)
            errorEvent.throwableProxy.message shouldBe "simulated db failure"
        } finally {
            purgerLogger.detachAppender(appender)
        }
    }

    test("error log reports zero progress when the first batch fails") {
        // The complementary case to the test above: when batch #1 throws, nothing was committed.
        // "0 batches (0 entries purged)" is the actionable signal that the failure was total --
        // distinct from a late failure where most of the work already landed. Guards against an
        // off-by-one (e.g. incrementing batches before the delete) that would mis-report 0 as 1.
        val callCount = AtomicInteger(0)
        val errorLogged = CountDownLatch(1)
        val store = stubStore(onRemove = { _, _ ->
            callCount.incrementAndGet()
            throw RuntimeException("first batch failed")
        })

        val purgerLogger = LoggerFactory.getLogger(OutboxPurger::class.java) as Logger
        val appender = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                super.append(event)
                if (event.level == Level.ERROR) errorLogged.countDown()
            }
        }.apply { start() }
        purgerLogger.addAppender(appender)

        try {
            val purger = OutboxPurger(
                outboxStore = store,
                transactionRunner = noOpTransactionRunner(),
                config = OutboxPurgerConfig(interval = ofMillis(50), batchSize = 100),
                clock = fixedClock,
            )
            purger.start()
            errorLogged.await(2, TimeUnit.SECONDS) shouldBe true
            purger.stop()

            val errorEvent = appender.list.first { it.level == Level.ERROR }
            errorEvent.argumentArray.toList() shouldBe listOf(0, 0)
            errorEvent.throwableProxy.message shouldBe "first batch failed"
        } finally {
            purgerLogger.detachAppender(appender)
        }
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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
            config = OutboxPurgerConfig(interval = ofMinutes(1), batchSize = 100),
            clock = fixedClock,
        )

        purger.start()
        purger.stop()

        shouldThrow<IllegalStateException> {
            purger.start()
        }.message shouldBe "OutboxPurger cannot be restarted after stop()"
    }

    test("transactionRunner wraps each batch delete") {
        val txInvocations = AtomicInteger(0)
        val storeInvocations = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val store = stubStore(onRemove = { _, _ ->
            val count = storeInvocations.incrementAndGet()
            if (count == 1) {
                100
            } else {
                latch.countDown()
                42
            }
        })
        val txRunner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T {
                txInvocations.incrementAndGet()
                return block()
            }
        }

        val purger = OutboxPurger(
            outboxStore = store,
            transactionRunner = txRunner,
            config = OutboxPurgerConfig(interval = ofMillis(50), batchSize = 100),
            clock = fixedClock,
        )
        purger.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        purger.stop()

        storeInvocations.get() shouldBe 2
        txInvocations.get() shouldBe storeInvocations.get()
    }
})

private fun noOpTransactionRunner() = object : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}

private fun stubStore(onRemove: (Instant, Int) -> Int = { _, _ -> 0 }) = object : OutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int): Int = onRemove(time, limit)
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}
