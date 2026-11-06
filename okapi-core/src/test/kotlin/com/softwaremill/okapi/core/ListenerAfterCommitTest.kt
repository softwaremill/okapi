package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val info = object : DeliveryInfo {
    override val type = "stub"
    override fun serialize() = "{}"
}

/** Hands out one fresh PENDING entry per claim. */
private class OneEntryPerClaimStore : RouteAwareOutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int): List<OutboxEntry> = error("not used")
    override fun claimPending(deliveryTypes: Set<String>, limit: Int): List<OutboxEntry> =
        listOf(OutboxEntry.createPending(OutboxMessage("e", "{}"), info, Instant.EPOCH))
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}

private class RecordingListener(private val log: MutableList<String>, private val onBatch: () -> Unit = {}) : OutboxProcessorListener {
    override fun onEntryProcessed(event: OutboxProcessingEvent) {
        log += "entry:${event::class.simpleName}"
    }

    override fun onBatchProcessed(processedCount: Int, duration: Duration) {
        log += "batch:$processedCount"
        onBatch()
    }
}

private fun processor(listener: OutboxProcessorListener) = OutboxProcessor(
    store = OneEntryPerClaimStore(),
    entryProcessor = OutboxEntryProcessor(
        object : MessageDeliverer {
            override val type = "stub"
            override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
        },
        RetryPolicy(maxRetries = 1),
        Clock.systemUTC(),
    ),
    listener = listener,
)

/** Issue #137: listener events describe committed state, so the scheduler emits them after the commit. */
class ListenerAfterCommitTest : FunSpec({
    test("processBatch persists without notifying; publishEvents notifies") {
        val log = mutableListOf<String>()
        val processor = processor(RecordingListener(log))

        val batch = processor.processBatch(10)
        batch.count shouldBe 1
        log.shouldBeEmpty()

        processor.publishEvents(batch)
        log shouldContainExactly listOf("entry:Delivered", "batch:1")
    }

    test("processNext still notifies at once") {
        val log = mutableListOf<String>()
        processor(RecordingListener(log)).processNext(10) shouldBe 1
        log shouldContainExactly listOf("entry:Delivered", "batch:1")
    }

    test("the scheduler emits events only after the transaction has committed") {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val batchSeen = CountDownLatch(1)
        val committingRunner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T = block().also { log += "commit" }
        }
        val scheduler = OutboxScheduler(
            processor(RecordingListener(log) { batchSeen.countDown() }),
            committingRunner,
            OutboxSchedulerConfig(interval = Duration.ofMinutes(1)),
        )

        scheduler.start()
        batchSeen.await(5, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        log.take(3) shouldContainExactly listOf("commit", "entry:Delivered", "batch:1")
    }

    test("a batch whose commit fails emits no events") {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val ticks = CountDownLatch(2)
        val failingCommit = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T {
                block()
                ticks.countDown()
                throw IllegalStateException("commit failed: connection reset")
            }
        }
        val scheduler = OutboxScheduler(
            processor(RecordingListener(log)),
            failingCommit,
            OutboxSchedulerConfig(interval = Duration.ofMillis(20)),
        )

        scheduler.start()
        ticks.await(5, TimeUnit.SECONDS) shouldBe true
        scheduler.stop()

        log.shouldBeEmpty()
    }
})
