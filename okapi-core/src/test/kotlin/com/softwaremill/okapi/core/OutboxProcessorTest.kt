package com.softwaremill.okapi.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
private val stubDeliveryInfo =
    object : DeliveryInfo {
        override val type = "stub"

        override fun serialize(): String = """{"type":"stub"}"""
    }

private fun stubDeliverer(result: DeliveryResult) = object : MessageDeliverer {
    override val type = "stub"

    override fun deliver(entry: OutboxEntry) = result
}

private fun stubEntry(messageType: String = "test.event"): OutboxEntry =
    OutboxEntry.createPending(OutboxMessage(messageType, "{}"), stubDeliveryInfo, Instant.EPOCH)

class OutboxProcessorTest :
    BehaviorSpec({
        val processedEntries = mutableListOf<OutboxEntry>()
        var pendingEntries: List<OutboxEntry> = emptyList()

        val store =
            object : OutboxStore {
                override fun persist(entry: OutboxEntry): OutboxEntry = entry

                override fun claimPending(limit: Int): List<OutboxEntry> = pendingEntries.take(limit)

                override fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry = entry.also { processedEntries += it }

                override fun removeDeliveredBefore(time: Instant) = Unit

                override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()

                override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
            }

        beforeEach { processedEntries.clear() }

        given("processNext() with two pending entries") {
            `when`("deliverer always succeeds") {
                pendingEntries =
                    listOf(
                        stubEntry("order.created"),
                        stubEntry("payment.received"),
                    )
                val entryProcessor =
                    OutboxEntryProcessor(
                        deliverer = stubDeliverer(DeliveryResult.Success),
                        retryPolicy = RetryPolicy(maxRetries = 3),
                        clock = fixedClock,
                    )
                OutboxProcessor(store, entryProcessor).processNext(limit = 10)
                val results = processedEntries.toList()

                then("both entries are updated") {
                    results.size shouldBe 2
                }
                then("both are DELIVERED") {
                    results.all { it.status == OutboxStatus.DELIVERED } shouldBe true
                }
            }
        }

        given("processNext() with limit smaller than pending count") {
            `when`("3 entries pending, limit=2") {
                pendingEntries = listOf(stubEntry(), stubEntry(), stubEntry())
                val entryProcessor =
                    OutboxEntryProcessor(
                        deliverer = stubDeliverer(DeliveryResult.Success),
                        retryPolicy = RetryPolicy(maxRetries = 3),
                        clock = fixedClock,
                    )
                OutboxProcessor(store, entryProcessor).processNext(limit = 2)
                val results = processedEntries.toList()

                then("only 2 entries are processed") {
                    results.size shouldBe 2
                }
            }
        }

        given("processNext() with empty store") {
            `when`("called") {
                pendingEntries = emptyList()
                val entryProcessor =
                    OutboxEntryProcessor(
                        deliverer = stubDeliverer(DeliveryResult.Success),
                        retryPolicy = RetryPolicy(maxRetries = 3),
                        clock = fixedClock,
                    )
                OutboxProcessor(store, entryProcessor).processNext()
                val results = processedEntries.toList()

                then("nothing is updated") {
                    results.size shouldBe 0
                }
            }
        }

        given("processNext() when deliverer returns RetriableFailure") {
            `when`("entry has retries remaining") {
                pendingEntries = listOf(stubEntry())
                val entryProcessor =
                    OutboxEntryProcessor(
                        deliverer = stubDeliverer(DeliveryResult.RetriableFailure("connection refused")),
                        retryPolicy = RetryPolicy(maxRetries = 3),
                        clock = fixedClock,
                    )
                OutboxProcessor(store, entryProcessor).processNext()
                val results = processedEntries.toList()

                then("entry is updated with PENDING status") {
                    results.first().status shouldBe OutboxStatus.PENDING
                }
            }
        }
    })
