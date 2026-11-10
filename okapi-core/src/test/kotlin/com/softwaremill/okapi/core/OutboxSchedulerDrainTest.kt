package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val drainInfo = object : DeliveryInfo {
    override val type = "stub"
    override fun serialize() = "{}"
}

/** In-memory store that claims due PENDING entries in order, as the real stores do. */
private class BacklogStore(size: Int) : RouteAwareOutboxStore {
    private val rows = LinkedHashMap<OutboxId, OutboxEntry>()

    init {
        repeat(size) {
            val entry = OutboxEntry.createPending(OutboxMessage("e", "{}"), drainInfo, Instant.EPOCH)
            rows[entry.outboxId] = entry
        }
    }

    fun count(status: OutboxStatus): Int = synchronized(this) { rows.values.count { it.status == status } }

    override fun persist(entry: OutboxEntry) = synchronized(this) { entry.also { rows[it.outboxId] = it } }
    override fun claimPending(limit: Int): List<OutboxEntry> = error("not used")
    override fun claimPending(deliveryTypes: Set<String>, limit: Int, now: Instant): List<OutboxEntry> = synchronized(this) {
        rows.values.filter { it.status == OutboxStatus.PENDING && it.deliveryType in deliveryTypes && !it.nextAttemptAt.isAfter(now) }
            .take(limit)
            // Stands in for FOR UPDATE SKIP LOCKED: another worker must not claim these until they are updated.
            .onEach { rows[it.outboxId] = it.copy(status = OutboxStatus.FAILED, lastError = "claimed") }
    }
    override fun updateAfterProcessing(entry: OutboxEntry) = persist(entry)
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}

/** Records committed batch sizes; [onBatch] runs after each one. */
private class BatchRecorder(private val onBatch: (Int) -> Unit = {}) : OutboxProcessorListener {
    val batches: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    override fun onBatchProcessed(processedCount: Int, duration: Duration) {
        batches += processedCount
        onBatch(processedCount)
    }
}

private class CountingRunner : TransactionRunner {
    val transactions = AtomicInteger()
    override fun <T> runInTransaction(block: () -> T): T = block().also { transactions.incrementAndGet() }
}

private fun drainProcessor(store: BacklogStore, listener: OutboxProcessorListener, result: DeliveryResult = DeliveryResult.Success) =
    OutboxProcessor(
        store = store,
        entryProcessor = OutboxEntryProcessor(
            object : MessageDeliverer {
                override val type = "stub"
                override fun deliver(entry: OutboxEntry) = result
            },
            RetryPolicy(maxRetries = 5),
            Clock.systemUTC(),
        ),
        listener = listener,
    )

/** Waits [millis] and asserts nothing more was processed, for checks that a tick stopped early. */
private fun assertQuiet(recorder: BatchRecorder, millis: Long = 300) {
    val before = recorder.batches.toList()
    Thread.sleep(millis)
    recorder.batches.toList() shouldBe before
}

/** Issue #132: a tick drains the backlog instead of processing one batch per worker. */
class OutboxSchedulerDrainTest : FunSpec({
    test("one tick drains the whole backlog, each batch in its own transaction") {
        val store = BacklogStore(35)
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it < 10) drained.countDown() }
        val runner = CountingRunner()
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder),
            runner,
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10),
        )

        scheduler.start()
        drained.await(5, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 35
        recorder.batches shouldContainExactly listOf(10, 10, 10, 5)
        runner.transactions.get() shouldBe 4
    }

    test("an exactly full last batch is followed by one empty claim, then the tick ends") {
        val store = BacklogStore(20)
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it == 0) drained.countDown() }
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder),
            CountingRunner(),
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10),
        )

        scheduler.start()
        drained.await(5, TimeUnit.SECONDS) shouldBe true
        assertQuiet(recorder)
        scheduler.stop()

        recorder.batches shouldContainExactly listOf(10, 10, 0)
    }

    test("maxBatchesPerTick = 1 keeps the 1.x one batch per tick") {
        val store = BacklogStore(35)
        val firstBatch = CountDownLatch(1)
        val recorder = BatchRecorder { firstBatch.countDown() }
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder),
            CountingRunner(),
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10, maxBatchesPerTick = 1),
        )

        scheduler.start()
        firstBatch.await(5, TimeUnit.SECONDS) shouldBe true
        assertQuiet(recorder)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 10
        recorder.batches shouldContainExactly listOf(10)
    }

    test("the tick stops at maxBatchesPerTick") {
        val store = BacklogStore(100)
        val third = CountDownLatch(3)
        val recorder = BatchRecorder { third.countDown() }
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder),
            CountingRunner(),
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10, maxBatchesPerTick = 3),
        )

        scheduler.start()
        third.await(5, TimeUnit.SECONDS) shouldBe true
        assertQuiet(recorder)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 30
    }

    test("a full batch whose entries were all rescheduled for retry ends the tick") {
        val store = BacklogStore(35)
        val firstBatch = CountDownLatch(1)
        val recorder = BatchRecorder { firstBatch.countDown() }
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder, DeliveryResult.RetriableFailure("503")),
            CountingRunner(),
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10),
        )

        scheduler.start()
        firstBatch.await(5, TimeUnit.SECONDS) shouldBe true
        assertQuiet(recorder)
        scheduler.stop()

        // The downstream is failing: only one batch is sent to it, not the whole backlog.
        recorder.batches shouldContainExactly listOf(10)
        store.count(OutboxStatus.PENDING) shouldBe 35
    }

    test("permanent failures are progress, so the tick keeps draining") {
        val store = BacklogStore(35)
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it < 10) drained.countDown() }
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder, DeliveryResult.PermanentFailure("400")),
            CountingRunner(),
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10),
        )

        scheduler.start()
        drained.await(5, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        store.count(OutboxStatus.FAILED) shouldBe 35
    }

    test("a failed transaction ends the tick; the next tick resumes") {
        val store = BacklogStore(35)
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it < 10) drained.countDown() }
        val attempts = AtomicInteger()
        val failSecond = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T {
                if (attempts.incrementAndGet() == 2) throw IllegalStateException("connection reset")
                return block()
            }
        }
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder),
            failSecond,
            OutboxSchedulerConfig(interval = Duration.ofMillis(200), batchSize = 10),
        )

        scheduler.start()
        drained.await(5, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        // Tick 1: one batch, then the failure. Tick 2 drains the rest.
        recorder.batches shouldContainExactly listOf(10, 10, 10, 5)
        attempts.get() shouldBe 5
    }

    test("concurrent workers each drain until the backlog is empty") {
        val store = BacklogStore(200)
        val recorder = BatchRecorder()
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder),
            CountingRunner(),
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10, concurrency = 4),
        )

        scheduler.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (store.count(OutboxStatus.DELIVERED) < 200 && System.nanoTime() < deadline) Thread.sleep(10)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 200
        recorder.batches.sum() shouldBe 200
    }

    test("stop() during a drain finishes the batch in progress and starts no further one") {
        val store = BacklogStore(100_000)
        val fewBatches = CountDownLatch(3)
        val recorder = BatchRecorder { fewBatches.countDown() }
        val scheduler = OutboxScheduler(
            drainProcessor(store, recorder),
            CountingRunner(),
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10),
        )

        scheduler.start()
        fewBatches.await(5, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        val processed = recorder.batches.sum()
        store.count(OutboxStatus.DELIVERED) shouldBe processed
        processed shouldBeLessThan 10 * OutboxSchedulerConfig.DEFAULT_MAX_BATCHES_PER_TICK
        assertQuiet(recorder)
    }
})
