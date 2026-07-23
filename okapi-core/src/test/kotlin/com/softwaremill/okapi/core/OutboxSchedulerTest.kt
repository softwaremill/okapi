package com.softwaremill.okapi.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.time.Duration.ofMillis
import java.time.Duration.ofMinutes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
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
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(interval = ofMinutes(1)),
        )

        scheduler.start()
        scheduler.stop()

        shouldThrow<IllegalStateException> {
            scheduler.start()
        }.message shouldBe "OutboxScheduler cannot be restarted after stop()"
    }

    test("transactionRunner wraps tick") {
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

    test("concurrency > 1 fans out to that many workers per tick") {
        val callCount = AtomicInteger(0)
        val threadNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val latch = CountDownLatch(4)
        val processor = stubProcessor { _ ->
            callCount.incrementAndGet()
            threadNames += Thread.currentThread().name
            latch.countDown()
        }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(interval = ofMinutes(1), concurrency = 4),
        )

        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        callCount.get() shouldBe 4
        threadNames shouldBe setOf("outbox-worker-1", "outbox-worker-2", "outbox-worker-3", "outbox-worker-4")
    }

    test("concurrency = 1 never invokes workerExecutorFactory (zero-overhead default path)") {
        val factoryCalled = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val processor = stubProcessor { _ -> latch.countDown() }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(
                interval = ofMillis(50),
                concurrency = 1,
                workerExecutorFactory = { n ->
                    factoryCalled.set(true)
                    OutboxSchedulerConfig.defaultPlatformPool(n)
                },
            ),
        )

        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        factoryCalled.get() shouldBe false
    }

    test("one worker's exception does not prevent the other workers from completing") {
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(4)
        val processor = stubProcessor { _ ->
            val count = callCount.incrementAndGet()
            latch.countDown()
            if (count == 1) throw RuntimeException("worker failure")
        }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(interval = ofMinutes(1), concurrency = 4),
        )

        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        callCount.get() shouldBe 4
    }

    test("a spurious interrupt while running does not let ticks overlap (awaitWorker keeps waiting)") {
        val workerStarted = CountDownLatch(2)
        val releaseWorkers = CountDownLatch(1)
        val processor = stubProcessor { _ ->
            workerStarted.countDown()
            releaseWorkers.await()
        }
        // Counts pool.submit() calls themselves, not worker executions: with a fixed 2-thread pool
        // already saturated by the blocked first-round workers, an erroneous second tick's
        // submissions would just sit queued behind them, never actually running -- so counting
        // executions would miss the overlap entirely. Counting submissions catches it regardless.
        val submitCount = AtomicInteger(0)

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(
                interval = ofMillis(20),
                concurrency = 2,
                workerExecutorFactory = { n -> SubmitCountingExecutor(OutboxSchedulerConfig.defaultPlatformPool(n), submitCount) },
            ),
        )

        try {
            scheduler.start()
            workerStarted.await(2, TimeUnit.SECONDS) shouldBe true
            submitCount.get() shouldBe 2

            // Interrupt the scheduler's own internal thread directly -- NOT via stop() -- to
            // simulate a spurious interrupt while the scheduler is still meant to be running
            // normally (running is still true). Without the fix, awaitWorker() would abandon the
            // wait, tick() would return, and scheduleWithFixedDelay would start a new tick while
            // these two workers are still blocked -- exactly the overlap the class forbids.
            val schedulerThread = Thread.getAllStackTraces().keys.first { it.name == "outbox-processor" }
            schedulerThread.interrupt()

            // Several intervals' worth of margin for a buggy early-return to manifest as extra
            // submissions before the original two workers are ever released.
            Thread.sleep(200)
            submitCount.get() shouldBe 2

            releaseWorkers.countDown()
        } finally {
            releaseWorkers.countDown()
            scheduler.stop()
        }
    }

    test("RejectedExecutionException submitting a worker does not kill the scheduler") {
        val concurrency = 2
        // workerExecutorFactory only runs on the scheduler's background thread on the first
        // tick(), so a plain lateinit var raced with this test thread's polling -- an
        // AtomicReference makes the handoff safe.
        val rejectingExecutorRef = AtomicReference<AlwaysRejectingExecutor?>(null)
        val processor = stubProcessor { _ -> }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(
                interval = ofMillis(20),
                concurrency = concurrency,
                workerExecutorFactory = { n ->
                    AlwaysRejectingExecutor(OutboxSchedulerConfig.defaultPlatformPool(n)).also { rejectingExecutorRef.set(it) }
                },
            ),
        )

        scheduler.start()
        // Without the fix, the first rejection escapes tick() and scheduleWithFixedDelay
        // suppresses all further ticks, so submitAttempts would get stuck at `concurrency` (one
        // tick's worth). Observing it climb past that proves later ticks still ran.
        awaitCondition { (rejectingExecutorRef.get()?.submitAttempts?.get() ?: 0) > concurrency }
        scheduler.stop()

        rejectingExecutorRef.get()!!.submitAttempts.get() shouldBeGreaterThanOrEqualTo concurrency
    }

    test("workerExecutorFactory failure during lazy init does not kill the scheduler, and retries next tick") {
        val factoryCallCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val processor = stubProcessor { _ -> latch.countDown() }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(
                interval = ofMillis(20),
                concurrency = 2,
                workerExecutorFactory = { n ->
                    // Fails on the very first attempt (simulating e.g. resource exhaustion), then
                    // succeeds -- Lazy retries its initializer after a failed attempt, so this
                    // proves both that tick() survives the failure and that the next tick retries.
                    if (factoryCallCount.incrementAndGet() == 1) {
                        throw RuntimeException("simulated worker pool creation failure")
                    }
                    OutboxSchedulerConfig.defaultPlatformPool(n)
                },
            ),
        )

        scheduler.start()
        // Without the fix, the first factory failure escapes tick() and scheduleWithFixedDelay
        // suppresses all further ticks, so this would never reach processBatch and time out.
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        factoryCallCount.get() shouldBeGreaterThanOrEqualTo 2
    }

    test("interrupting the caller during stop() restores the flag and still shuts down without throwing") {
        val processingStarted = CountDownLatch(1)
        val releaseProcessing = CountDownLatch(1)
        val processor = stubProcessor { _ ->
            processingStarted.countDown()
            releaseProcessing.await()
        }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(interval = ofMillis(50)),
        )

        scheduler.start()
        processingStarted.await(2, TimeUnit.SECONDS) shouldBe true

        var stopThrew = false
        var interruptedAfterReturn = false
        val stopper = Thread {
            try {
                scheduler.stop()
            } catch (_: Throwable) {
                stopThrew = true
            }
            interruptedAfterReturn = Thread.currentThread().isInterrupted
        }
        stopper.start()
        awaitBlocked(stopper)
        stopper.interrupt()
        awaitTermination(stopper)

        stopThrew shouldBe false
        interruptedAfterReturn shouldBe true
    }

    test("stop() before start() is a no-op") {
        val processor = stubProcessor { _ -> }
        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(interval = ofMinutes(1)),
        )

        scheduler.stop() // must return, not throw, despite never having started

        scheduler.isRunning() shouldBe false
    }

    test("calling stop() a second time is a no-op") {
        val latch = CountDownLatch(1)
        val processor = stubProcessor { _ -> latch.countDown() }
        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(interval = ofMillis(50)),
        )

        scheduler.start()
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()
        scheduler.stop() // running is already false -- must short-circuit, not re-run shutdown logic

        scheduler.isRunning() shouldBe false
    }

    test(
        "interrupting the caller during stop() with an active worker pool logs a warning from " +
            "awaitWorker and force-shuts-down the worker pool too, all without throwing",
    ) {
        // Both workers block (simulating a stuck delivery), so the scheduler's own thread is stuck
        // inside awaitWorker()'s future.get() when stop() is called. A single interrupt on the
        // stop()-caller cascades through both executors: it forces scheduler.shutdownNow(), which
        // interrupts the scheduler thread -- awaitWorker() must catch that itself (not hang, not
        // crash) and log a warning. That restores the caller's own interrupt flag, so when stop()
        // moves on to the workers pool's shutdownAndAwait, its awaitTermination sees the flag still
        // set and immediately treats it as interrupted too, force-shutting-down the worker pool
        // without a second explicit interrupt.
        val workerStarted = CountDownLatch(2)
        val releaseWorkers = CountDownLatch(1)
        val processor = stubProcessor { _ ->
            workerStarted.countDown()
            releaseWorkers.await()
        }

        val scheduler = OutboxScheduler(
            outboxProcessor = processor,
            transactionRunner = noOpTransactionRunner(),
            config = OutboxSchedulerConfig(interval = ofMinutes(1), concurrency = 2),
        )

        val schedulerLogger = LoggerFactory.getLogger(OutboxScheduler::class.java) as Logger
        val warnLogged = CountDownLatch(1)
        val appender = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                super.append(event)
                if (event.level == Level.WARN) warnLogged.countDown()
            }
        }.apply { start() }
        schedulerLogger.addAppender(appender)

        try {
            scheduler.start()
            workerStarted.await(2, TimeUnit.SECONDS) shouldBe true

            var stopThrew = false
            val stopper = Thread {
                try {
                    scheduler.stop()
                } catch (_: Throwable) {
                    stopThrew = true
                }
            }
            stopper.start()

            awaitBlocked(stopper)
            stopper.interrupt()
            releaseWorkers.countDown() // defensive: shutdownNow() already interrupts the workers too
            awaitTermination(stopper)

            warnLogged.await(2, TimeUnit.SECONDS) shouldBe true
            stopThrew shouldBe false
        } finally {
            schedulerLogger.detachAppender(appender)
        }
    }
})

/**
 * Always rejects submissions, tracking how many attempts were made -- used to prove
 * [OutboxScheduler] doesn't die when [OutboxSchedulerConfig.workerExecutorFactory] returns an
 * executor that rejects.
 */
private class AlwaysRejectingExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {
    val submitAttempts = AtomicInteger(0)

    override fun submit(task: Runnable): Future<*> {
        submitAttempts.incrementAndGet()
        throw RejectedExecutionException("worker pool full (test double)")
    }

    override fun close() {
        delegate.close()
    }
}

/** Counts [submit] calls (queued or not) without altering delegate behavior otherwise. */
private class SubmitCountingExecutor(
    private val delegate: ExecutorService,
    private val submitCount: AtomicInteger,
) : ExecutorService by delegate {
    override fun submit(task: Runnable): Future<*> {
        submitCount.incrementAndGet()
        return delegate.submit(task)
    }

    override fun close() {
        delegate.close()
    }
}

private fun awaitCondition(timeoutMs: Long = 2_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Condition not met within ${timeoutMs}ms" }
        Thread.sleep(5)
    }
}

/**
 * Polls (rather than sleeping a fixed duration) until [thread] is parked waiting, so the test
 * isn't sensitive to CI scheduling jitter -- interrupting before the thread actually blocks would
 * make it miss the wait entirely.
 */
private fun awaitBlocked(thread: Thread, timeoutMs: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
        check(System.currentTimeMillis() < deadline) { "Thread never blocked (state=${thread.state})" }
        Thread.sleep(5)
    }
}

/**
 * A timed [Thread.join] only establishes a happens-before edge for the joined thread's writes if
 * it returns because the thread actually terminated, not because the timeout elapsed. Confirming
 * termination and then joining again unconditionally (returns immediately on an already-dead
 * thread) closes that gap.
 */
private fun awaitTermination(thread: Thread, timeoutMs: Long = 5_000) {
    thread.join(timeoutMs)
    check(!thread.isAlive) { "Thread did not terminate within ${timeoutMs}ms" }
    thread.join()
}

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
