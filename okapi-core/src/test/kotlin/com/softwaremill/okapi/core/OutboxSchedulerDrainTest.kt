package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class TypeInfo(override val type: String) : DeliveryInfo {
    override fun serialize() = "{}"
}

/** In-memory store that claims due PENDING entries in order, as the real stores do. */
private class BacklogStore(backlog: Map<String, Int>) : RouteAwareOutboxStore {
    constructor(size: Int) : this(mapOf("stub" to size))

    private val rows = LinkedHashMap<OutboxId, OutboxEntry>()

    init {
        backlog.forEach { (type, size) ->
            repeat(size) {
                val entry = OutboxEntry.createPending(OutboxMessage("e", "{}"), TypeInfo(type), Instant.EPOCH)
                rows[entry.outboxId] = entry
            }
        }
    }

    fun count(status: OutboxStatus, type: String? = null): Int = synchronized(this) {
        rows.values.count { it.status == status && (type == null || it.deliveryType == type) }
    }

    fun ids(): List<OutboxId> = synchronized(this) { rows.keys.toList() }

    fun retries(type: String): List<Int> = synchronized(this) { rows.values.filter { it.deliveryType == type }.map { it.retries } }

    override fun persist(entry: OutboxEntry) = synchronized(this) { entry.also { rows[it.outboxId] = it } }
    override fun claimPending(limit: Int): List<OutboxEntry> = error("not used")
    override fun claimPending(deliveryTypes: Set<String>, limit: Int, now: Instant): List<OutboxEntry> = synchronized(this) {
        rows.values.filter { it.status == OutboxStatus.PENDING && it.deliveryType in deliveryTypes && !it.nextAttemptAt.isAfter(now) }
            .sortedWith(compareBy({ it.deliveryType }, { it.nextAttemptAt }))
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

private fun delivererOf(type: String, result: (OutboxEntry) -> DeliveryResult) = object : MessageDeliverer {
    override val type = type
    override fun deliver(entry: OutboxEntry) = result(entry)
}

private fun drainProcessor(
    store: BacklogStore,
    listener: OutboxProcessorListener,
    deliverer: MessageDeliverer = delivererOf("stub") { DeliveryResult.Success },
    retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 5),
) = OutboxProcessor(store, OutboxEntryProcessor(deliverer, retryPolicy, Clock.systemUTC()), listener)

private fun scheduler(processor: OutboxProcessor, runner: TransactionRunner = CountingRunner(), config: OutboxSchedulerConfig) =
    OutboxScheduler(processor, runner, config)

private val oneTickOnly = OutboxSchedulerConfig(interval = Duration.ofMinutes(1), batchSize = 10)

/**
 * Waits [millis] and asserts nothing more was processed, for checks that a tick stopped early.
 * It cannot fail spuriously, only miss a late extra batch.
 */
private fun assertQuiet(recorder: BatchRecorder, millis: Long = 300) {
    val before = recorder.batches.toList()
    Thread.sleep(millis)
    recorder.batches.toList() shouldBe before
}

private fun OutboxScheduler.runFirstTick(done: CountDownLatch) {
    start()
    done.await(5, TimeUnit.SECONDS) shouldBe true
}

/** Issue #132: a tick drains the backlog instead of processing one batch per worker. */
class OutboxSchedulerDrainTest : FunSpec({
    test("one tick drains the whole backlog, each batch in its own transaction") {
        val store = BacklogStore(35)
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it < 10) drained.countDown() }
        val runner = CountingRunner()
        val scheduler = scheduler(drainProcessor(store, recorder), runner, oneTickOnly)

        scheduler.runFirstTick(drained)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 35
        recorder.batches shouldContainExactly listOf(10, 10, 10, 5)
        runner.transactions.get() shouldBe 4
    }

    test("an exactly full last batch is followed by one empty claim, then the tick ends") {
        val store = BacklogStore(20)
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it == 0) drained.countDown() }
        val scheduler = scheduler(drainProcessor(store, recorder), config = oneTickOnly)

        scheduler.runFirstTick(drained)
        assertQuiet(recorder)
        scheduler.stop()

        recorder.batches shouldContainExactly listOf(10, 10, 0)
    }

    test("maxBatchesPerTick = 1 keeps the 1.x one batch per tick") {
        val store = BacklogStore(35)
        val firstBatch = CountDownLatch(1)
        val recorder = BatchRecorder { firstBatch.countDown() }
        val scheduler = scheduler(drainProcessor(store, recorder), config = oneTickOnly.copy(maxBatchesPerTick = 1))

        scheduler.runFirstTick(firstBatch)
        assertQuiet(recorder)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 10
        recorder.batches shouldContainExactly listOf(10)
    }

    test("the tick stops at maxBatchesPerTick") {
        val store = BacklogStore(100)
        val third = CountDownLatch(3)
        val recorder = BatchRecorder { third.countDown() }
        val scheduler = scheduler(drainProcessor(store, recorder), config = oneTickOnly.copy(maxBatchesPerTick = 3))

        scheduler.runFirstTick(third)
        assertQuiet(recorder)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 30
    }

    test("a full batch whose entries were all rescheduled for retry ends the tick") {
        val store = BacklogStore(35)
        val firstBatch = CountDownLatch(1)
        val recorder = BatchRecorder { firstBatch.countDown() }
        val failing = delivererOf("stub") { DeliveryResult.RetriableFailure("503") }
        val scheduler = scheduler(drainProcessor(store, recorder, failing), config = oneTickOnly)

        scheduler.runFirstTick(firstBatch)
        assertQuiet(recorder)
        scheduler.stop()

        // The downstream is failing: only one batch is sent to it, not the whole backlog.
        recorder.batches shouldContainExactly listOf(10)
        store.count(OutboxStatus.PENDING) shouldBe 35
    }

    test("a type that only fails for good gets one batch per tick, so a misconfiguration does not fail the backlog at full speed") {
        val store = BacklogStore(35)
        val firstBatch = CountDownLatch(1)
        val recorder = BatchRecorder { firstBatch.countDown() }
        val rejecting = delivererOf("stub") { DeliveryResult.PermanentFailure("401") }
        val scheduler = scheduler(drainProcessor(store, recorder, rejecting), config = oneTickOnly)

        scheduler.runFirstTick(firstBatch)
        assertQuiet(recorder)
        scheduler.stop()

        store.count(OutboxStatus.FAILED) shouldBe 10
        store.count(OutboxStatus.PENDING) shouldBe 25
    }

    test("a batch with some deliveries and some retries keeps draining") {
        val store = BacklogStore(35)
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it < 10) drained.countDown() }
        val calls = AtomicInteger()
        val flaky = delivererOf("stub") {
            if (calls.incrementAndGet() % 3 == 0) DeliveryResult.RetriableFailure("503") else DeliveryResult.Success
        }
        val scheduler = scheduler(drainProcessor(store, recorder, flaky), config = oneTickOnly)

        scheduler.runFirstTick(drained)
        scheduler.stop()

        recorder.batches shouldContainExactly listOf(10, 10, 10, 5)
        store.count(OutboxStatus.PENDING) shouldBe 11
    }

    test("a failing delivery type is skipped for the rest of the tick while a healthy one keeps draining") {
        val store = BacklogStore(mapOf("a" to 500, "b" to 500))
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it < 10) drained.countDown() }
        val deliverer = CompositeMessageDeliverer(
            listOf(
                delivererOf("a") { DeliveryResult.Success },
                delivererOf("b") { DeliveryResult.RetriableFailure("503") },
            ),
        )
        val scheduler = scheduler(drainProcessor(store, recorder, deliverer), config = oneTickOnly)

        scheduler.runFirstTick(drained)
        assertQuiet(recorder)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED, "a") shouldBe 500
        // The failing downstream is sent one batch in the tick, not b's whole backlog.
        store.retries("b").count { it > 0 } shouldBe 10
    }

    test("with a zero backoff, an entry retried during the tick waits for the next tick") {
        val store = BacklogStore(200)
        val failing = store.ids().take(3).toSet()
        val drained = CountDownLatch(1)
        val recorder = BatchRecorder { if (it < 10) drained.countDown() }
        // Same type as the healthy entries, so only the tick's claim time keeps the retries out.
        val deliverer = delivererOf("stub") {
            if (it.outboxId in failing) DeliveryResult.RetriableFailure("503") else DeliveryResult.Success
        }
        val noBackoff = RetryPolicy(maxRetries = 5, initialBackoff = Duration.ZERO, maxBackoff = Duration.ZERO)
        val scheduler = scheduler(drainProcessor(store, recorder, deliverer, noBackoff), config = oneTickOnly)

        scheduler.runFirstTick(drained)
        assertQuiet(recorder)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 197
        store.retries("stub").filter { it > 0 } shouldContainExactly listOf(1, 1, 1)
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
        val scheduler = scheduler(drainProcessor(store, recorder), failSecond, OutboxSchedulerConfig(interval = Duration.ofMillis(200)))

        scheduler.runFirstTick(drained)
        scheduler.stop()

        // Tick 1: one batch, then the failure. Tick 2 drains the rest.
        recorder.batches shouldContainExactly listOf(10, 10, 10, 5)
        attempts.get() shouldBe 5
    }

    test("concurrent workers each drain until the backlog is empty") {
        val store = BacklogStore(200)
        val recorder = BatchRecorder()
        val scheduler = scheduler(drainProcessor(store, recorder), config = oneTickOnly.copy(concurrency = 4))

        scheduler.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (store.count(OutboxStatus.DELIVERED) < 200 && System.nanoTime() < deadline) Thread.sleep(10)
        scheduler.stop()

        store.count(OutboxStatus.DELIVERED) shouldBe 200
        recorder.batches.sum() shouldBe 200
    }

    test("stop() during a drain finishes the batch in flight and starts no further one") {
        val store = BacklogStore(1_000)
        val inThirdBatch = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = AtomicInteger()
        val blocking = delivererOf("stub") {
            if (delivered.incrementAndGet() == 21) {
                inThirdBatch.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            DeliveryResult.Success
        }
        val recorder = BatchRecorder()
        val scheduler = scheduler(drainProcessor(store, recorder, blocking), config = oneTickOnly)

        scheduler.start()
        inThirdBatch.await(5, TimeUnit.SECONDS) shouldBe true
        val stopping = Thread { scheduler.stop() }.apply { start() }
        Thread.sleep(100) // let stop() clear the running flag while the third batch is still in flight
        release.countDown()
        stopping.join(10_000)

        stopping.isAlive shouldBe false
        recorder.batches shouldContainExactly listOf(10, 10, 10)
        store.count(OutboxStatus.DELIVERED) shouldBe 30
    }
})
