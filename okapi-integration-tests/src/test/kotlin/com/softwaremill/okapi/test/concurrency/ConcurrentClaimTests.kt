package com.softwaremill.okapi.test.concurrency

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.test.support.RecordingMessageDeliverer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class StubDeliveryInfo(
    override val type: String = "recording",
    private val metadata: String = """{"type":"recording"}""",
) : DeliveryInfo {
    override fun serialize(): String = metadata
}

private fun createTestEntry(index: Int, now: Instant = Instant.parse("2024-01-01T00:00:00Z")): OutboxEntry = OutboxEntry.createPending(
    message = OutboxMessage(messageType = "concurrent.test", payload = """{"index":$index}"""),
    deliveryInfo = StubDeliveryInfo(),
    now = now.plusSeconds(index.toLong()),
)

fun FunSpec.concurrentClaimTests(
    dbName: String,
    storeFactory: () -> OutboxStore,
    startDb: () -> Unit,
    stopDb: () -> Unit,
    truncate: () -> Unit,
) {
    lateinit var store: OutboxStore

    beforeSpec {
        startDb()
        store = storeFactory()
    }

    afterSpec {
        stopDb()
    }

    beforeEach {
        truncate()
    }

    test("[$dbName] concurrent claimPending with held locks produces disjoint sets") {
        // Insert 20 entries
        val allIds = transaction {
            (0 until 20).map { i ->
                val entry = createTestEntry(i)
                store.persist(entry)
                entry.outboxId
            }
        }

        val lockAcquired = CountDownLatch(1)
        val canCommit = CountDownLatch(1)
        val claimedByA = CompletableFuture<List<OutboxId>>()

        // Thread A: claim entries and hold the transaction open.
        // READ_COMMITTED is required for MySQL — under REPEATABLE_READ, InnoDB's
        // next-key locks cause SKIP LOCKED to skip more rows than actually locked.
        val threadA = Thread.ofVirtual().name("processor-A").start {
            try {
                transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                    val claimed = store.claimPending(10)
                    claimedByA.complete(claimed.map { it.outboxId })
                    lockAcquired.countDown()
                    // Hold locks open until main thread signals
                    canCommit.await(10, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                claimedByA.completeExceptionally(e)
            }
        }

        // Wait for Thread A to acquire locks
        lockAcquired.await(10, TimeUnit.SECONDS) shouldBe true

        // Main thread: claim remaining entries (SKIP LOCKED should skip A's locked rows)
        val idsA = claimedByA.get(10, TimeUnit.SECONDS)
        val idsB = transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            store.claimPending(10)
        }.map { it.outboxId }

        // Assert disjoint
        val intersection = idsA.toSet().intersect(idsB.toSet())
        withClue("Sets should be disjoint (overlap: $intersection, A claimed ${idsA.size}, B claimed ${idsB.size})") {
            intersection shouldHaveSize 0
        }

        // Together they cover all 20 entries
        val union = (idsA + idsB).toSet()
        withClue("Union of A (${idsA.size}) and B (${idsB.size}) should cover all ${allIds.size} entries") {
            union shouldHaveSize 20
            union shouldBe allIds.toSet()
        }

        // Let Thread A commit and finish
        canCommit.countDown()
        threadA.join(10_000)
    }

    test("[$dbName] concurrent processors cause no delivery amplification") {
        val fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

        // Insert 50 entries
        transaction {
            (0 until 50).forEach { i -> store.persist(createTestEntry(i)) }
        }

        val recorder = RecordingMessageDeliverer()
        val entryProcessor = OutboxEntryProcessor(recorder, RetryPolicy(maxRetries = 0), fixedClock)

        val barrier = CyclicBarrier(5)
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        val futures = (1..5).map {
            CompletableFuture.supplyAsync(
                {
                    barrier.await(10, TimeUnit.SECONDS)
                    transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                        OutboxProcessor(store, entryProcessor).processNext(limit = 50)
                    }
                },
                executor,
            )
        }

        // Wait for all threads to complete
        CompletableFuture.allOf(*futures.toTypedArray()).get(30, TimeUnit.SECONDS)
        executor.shutdown()

        // Verify no amplification
        recorder.assertNoAmplification()
        withClue("Expected exactly 50 unique deliveries from 5 concurrent processors, got ${recorder.deliveryCount()}") {
            recorder.deliveryCount() shouldBe 50
        }

        // Verify DB state
        val counts = transaction { store.countByStatuses() }
        withClue("DB state after concurrent processing: $counts") {
            counts shouldContain (OutboxStatus.DELIVERED to 50L)
            counts shouldContain (OutboxStatus.PENDING to 0L)
        }
    }
}
