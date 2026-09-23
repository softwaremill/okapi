package com.softwaremill.okapi.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val AWAIT_TIMEOUT_SECONDS = 10L

private fun deliveryInfo(t: String) = object : DeliveryInfo {
    override val type = t
    override fun serialize(): String = """{"type":"$t"}"""
}

private fun entryOfType(t: String, id: Int): OutboxEntry =
    OutboxEntry.createPending(OutboxMessage("evt-$id", "{}"), deliveryInfo(t), Instant.EPOCH)

private fun fixedDeliverer(t: String, result: DeliveryResult) = object : MessageDeliverer {
    override val type = t
    override fun deliver(entry: OutboxEntry): DeliveryResult = result
}

private fun batchDeliverer(t: String, batch: (List<OutboxEntry>) -> List<DeliveryOutcome>) = object : MessageDeliverer {
    override val type = t
    override fun deliver(entry: OutboxEntry): DeliveryResult = error("deliver() should not be used by deliverBatch")
    override fun deliverBatch(entries: List<OutboxEntry>): List<DeliveryOutcome> = batch(entries)
}

/**
 * Rendezvous deliverer: blocks until every other transport group in the batch has also entered
 * its `deliverBatch`. Sequential dispatch can never get past the barrier, so an overlap failure
 * surfaces as a timeout (mapped to a retriable failure by the composite) rather than a hang.
 */
private fun rendezvousDeliverer(t: String, barrier: CyclicBarrier) = batchDeliverer(t) { entries ->
    barrier.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    entries.map { DeliveryOutcome(it, DeliveryResult.Success) }
}

class CompositeMessageDelivererTest : FunSpec({
    test("deliverBatch groups entries by type, delegates to each transport, preserves input order") {
        val composite = CompositeMessageDeliverer(
            listOf(
                fixedDeliverer("kafka", DeliveryResult.Success),
                fixedDeliverer("http", DeliveryResult.RetriableFailure("503")),
            ),
        )
        val entries = listOf(
            entryOfType("kafka", 1),
            entryOfType("http", 2),
            entryOfType("kafka", 3),
            entryOfType("http", 4),
        )

        val results = composite.deliverBatch(entries)

        results.size shouldBe 4
        results.map { it.entry } shouldBe entries
        results[0].result shouldBe DeliveryResult.Success
        results[1].result shouldBe DeliveryResult.RetriableFailure("503")
        results[2].result shouldBe DeliveryResult.Success
        results[3].result shouldBe DeliveryResult.RetriableFailure("503")
    }

    test("deliverBatch fails permanently for entries with no registered deliverer") {
        val composite = CompositeMessageDeliverer(
            listOf(fixedDeliverer("kafka", DeliveryResult.Success)),
        )
        val entries = listOf(
            entryOfType("kafka", 1),
            entryOfType("missing", 2),
        )

        val results = composite.deliverBatch(entries)

        results.size shouldBe 2
        results[0].result shouldBe DeliveryResult.Success
        results[1].result.shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
        (results[1].result as DeliveryResult.PermanentFailure).error shouldContain "missing"
    }

    test("deliverBatch with empty input returns empty list") {
        val composite = CompositeMessageDeliverer(emptyList())
        composite.deliverBatch(emptyList()) shouldBe emptyList()
    }

    test("deliverBatch uses each transport's overridden deliverBatch (not just deliver)") {
        var batchCallsKafka = 0
        var batchCallsHttp = 0
        val kafkaDeliverer = object : MessageDeliverer {
            override val type = "kafka"
            override fun deliver(entry: OutboxEntry): DeliveryResult = DeliveryResult.Success
            override fun deliverBatch(entries: List<OutboxEntry>): List<DeliveryOutcome> {
                batchCallsKafka++
                return entries.map { DeliveryOutcome(it, DeliveryResult.Success) }
            }
        }
        val httpDeliverer = object : MessageDeliverer {
            override val type = "http"
            override fun deliver(entry: OutboxEntry): DeliveryResult = DeliveryResult.Success
            override fun deliverBatch(entries: List<OutboxEntry>): List<DeliveryOutcome> {
                batchCallsHttp++
                return entries.map { DeliveryOutcome(it, DeliveryResult.Success) }
            }
        }
        val composite = CompositeMessageDeliverer(listOf(kafkaDeliverer, httpDeliverer))

        composite.deliverBatch(
            listOf(
                entryOfType("kafka", 1),
                entryOfType("http", 2),
                entryOfType("kafka", 3),
            ),
        )

        batchCallsKafka shouldBe 1
        batchCallsHttp shouldBe 1
    }

    test("deliverBatch dispatches transport groups concurrently, not one after another") {
        // Three parties: each of the three groups must be inside deliverBatch at the same moment
        // for the barrier to trip, so the whole batch costs ~max(Tᵢ) rather than sum(Tᵢ).
        val barrier = CyclicBarrier(3)
        val composite = CompositeMessageDeliverer(
            listOf(
                rendezvousDeliverer("kafka", barrier),
                rendezvousDeliverer("http", barrier),
                rendezvousDeliverer("sqs", barrier),
            ),
        )
        val entries = listOf(
            entryOfType("kafka", 1),
            entryOfType("http", 2),
            entryOfType("sqs", 3),
            entryOfType("kafka", 4),
        )

        val results = composite.deliverBatch(entries)

        results.map { it.entry } shouldBe entries
        results.map { it.result } shouldBe List(4) { DeliveryResult.Success }
    }

    test("deliverBatch with a single transport group stays on the calling thread") {
        var deliveryThread: Thread? = null
        val composite = CompositeMessageDeliverer(
            listOf(
                batchDeliverer("kafka") { entries ->
                    deliveryThread = Thread.currentThread()
                    entries.map { DeliveryOutcome(it, DeliveryResult.Success) }
                },
            ),
        )

        val results = composite.deliverBatch(listOf(entryOfType("kafka", 1), entryOfType("kafka", 2)))

        results.map { it.result } shouldBe listOf(DeliveryResult.Success, DeliveryResult.Success)
        deliveryThread shouldBe Thread.currentThread()
    }

    test("SEQUENTIAL dispatch runs every transport group on the calling thread") {
        val deliveryThreads = ConcurrentLinkedQueue<Thread>()
        val recordingDeliverer = { t: String ->
            batchDeliverer(t) { entries ->
                deliveryThreads += Thread.currentThread()
                entries.map { DeliveryOutcome(it, DeliveryResult.Success) }
            }
        }
        val composite = CompositeMessageDeliverer(
            listOf(recordingDeliverer("kafka"), recordingDeliverer("http"), recordingDeliverer("sqs")),
            TransportDispatch.SEQUENTIAL,
        )
        val entries = listOf(entryOfType("kafka", 1), entryOfType("http", 2), entryOfType("sqs", 3), entryOfType("http", 4))

        val results = composite.deliverBatch(entries)

        results.map { it.entry } shouldBe entries
        results.map { it.result } shouldBe List(4) { DeliveryResult.Success }
        deliveryThreads.size shouldBe 3
        deliveryThreads.toSet() shouldBe setOf(Thread.currentThread())
    }

    test("SEQUENTIAL dispatch still isolates a throwing transport from the rest of the batch") {
        val composite = CompositeMessageDeliverer(
            listOf(
                fixedDeliverer("kafka", DeliveryResult.Success),
                batchDeliverer("http") { throw IllegalStateException("connection pool closed") },
            ),
            TransportDispatch.SEQUENTIAL,
        )
        val entries = listOf(entryOfType("kafka", 1), entryOfType("http", 2))

        val results = composite.deliverBatch(entries)

        results[0].result shouldBe DeliveryResult.Success
        results[1].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>().error shouldContain "connection pool closed"
    }

    test("a transport that throws fails only its own entries, retriably") {
        val composite = CompositeMessageDeliverer(
            listOf(
                fixedDeliverer("kafka", DeliveryResult.Success),
                batchDeliverer("http") { throw IllegalStateException("connection pool closed") },
            ),
        )
        val entries = listOf(entryOfType("kafka", 1), entryOfType("http", 2), entryOfType("http", 3))

        val results = composite.deliverBatch(entries)

        results.map { it.entry } shouldBe entries
        results[0].result shouldBe DeliveryResult.Success
        results[1].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>().error shouldContain "connection pool closed"
        results[2].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>().error shouldContain "connection pool closed"
    }

    test("a single throwing transport is caught on the fast path too") {
        val composite = CompositeMessageDeliverer(
            listOf(batchDeliverer("kafka") { throw IllegalStateException("producer closed") }),
        )

        val results = composite.deliverBatch(listOf(entryOfType("kafka", 1)))

        results.size shouldBe 1
        results[0].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>().error shouldContain "producer closed"
    }

    test("entries a transport returns no result for fail retriably instead of throwing") {
        val composite = CompositeMessageDeliverer(
            listOf(
                batchDeliverer("kafka") { entries -> entries.take(1).map { DeliveryOutcome(it, DeliveryResult.Success) } },
            ),
        )
        val entries = listOf(entryOfType("kafka", 1), entryOfType("kafka", 2))

        val results = composite.deliverBatch(entries)

        results.map { it.entry } shouldBe entries
        results[0].result shouldBe DeliveryResult.Success
        results[1].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>().error shouldContain "no result"
    }

    test("a transport that ignores interruption cannot hold up the batch once the caller is interrupted") {
        val delivererStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Models a transport that swallows interruption — exactly the task ExecutorService.close()
        // would wait on (awaitTermination(1, DAYS) in a loop) before letting deliverBatch return.
        val uninterruptible = batchDeliverer("kafka") { entries ->
            delivererStarted.countDown()
            var released = false
            while (!released) {
                released = try {
                    release.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    false
                }
            }
            entries.map { DeliveryOutcome(it, DeliveryResult.Success) }
        }
        val composite = CompositeMessageDeliverer(listOf(uninterruptible, fixedDeliverer("http", DeliveryResult.Success)))
        val results = AtomicReference<List<DeliveryOutcome>>()
        val caller = Thread.ofVirtual().unstarted {
            results.set(composite.deliverBatch(listOf(entryOfType("kafka", 1), entryOfType("http", 2))))
        }

        try {
            caller.start()
            delivererStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            caller.interrupt()

            withClue("deliverBatch must not wait for a transport group it has already given up on") {
                caller.join(Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS)) shouldBe true
            }
            // Still latched: deliverBatch returned while that transport was demonstrably still running.
            release.count shouldBe 1L
            results.get()[0].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
            results.get()[1].result shouldBe DeliveryResult.Success
        } finally {
            release.countDown()
            caller.join()
        }
    }

    test("interrupting the caller while a transport group is in flight yields retriable results and restores the flag") {
        val delivererStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = batchDeliverer("kafka") { entries ->
            delivererStarted.countDown()
            try {
                release.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                entries.map { DeliveryOutcome(it, DeliveryResult.Success) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                entries.map { DeliveryOutcome(it, DeliveryResult.RetriableFailure(e.javaClass.simpleName)) }
            }
        }
        val composite = CompositeMessageDeliverer(listOf(blocking, fixedDeliverer("http", DeliveryResult.Success)))
        val caller = Thread.currentThread()
        val interrupter = Thread.ofVirtual().start {
            delivererStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            caller.interrupt()
        }

        try {
            // "kafka" is the first-seen type, so it runs on a virtual thread while "http" — the last
            // group — runs inline on this thread; the interrupt therefore lands while awaiting kafka.
            val results = composite.deliverBatch(listOf(entryOfType("kafka", 1), entryOfType("http", 2)))

            results[0].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>().error shouldContain "Interrupted"
            results[1].result shouldBe DeliveryResult.Success
            // Consumes the flag the composite restored, so it cannot leak into the next test.
            Thread.interrupted() shouldBe true
        } finally {
            release.countDown()
            interrupter.join()
            Thread.interrupted()
        }
    }
})
