package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant

private fun routeEntry(type: String, seconds: Long = 0): OutboxEntry = OutboxEntry.createPending(
    OutboxMessage("event", "{}"),
    object : DeliveryInfo {
        override val type = type
        override fun serialize() = "{}"
    },
    Instant.EPOCH.plusSeconds(seconds),
)

private fun routeDeliverer(type: String) = object : MessageDeliverer {
    override val type = type
    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
}

private class RoutingStore(var pending: List<OutboxEntry>) : RouteAwareOutboxStore {
    val queriedTypes = mutableListOf<Set<String>>()
    val processed = mutableListOf<OutboxEntry>()

    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int): List<OutboxEntry> = error("Unsafe claimPending(limit) must not be used")
    override fun claimPending(deliveryTypes: Set<String>, limit: Int): List<OutboxEntry> {
        queriedTypes += deliveryTypes
        val claimed = pending.filter { it.deliveryType in deliveryTypes }.take(limit)
        pending = pending - claimed.toSet()
        return claimed
    }
    override fun updateAfterProcessing(entry: OutboxEntry) = entry.also { processed += it }
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}

class RouteAwareOutboxProcessorTest : FunSpec({
    test("a single deliverer refuses an entry of another type before delivery") {
        val entryProcessor = OutboxEntryProcessor(routeDeliverer("kafka"), RetryPolicy(1), Clock.systemUTC())

        shouldThrow<IllegalStateException> { entryProcessor.process(routeEntry("http")) }
    }

    test("older unsupported entries are never claimed or failed") {
        val unsupported = routeEntry("http")
        val supported = routeEntry("kafka", 1)
        val store = RoutingStore(listOf(unsupported, supported))
        val processor = OutboxProcessor(
            store,
            OutboxEntryProcessor(routeDeliverer("kafka"), RetryPolicy(1), Clock.systemUTC()),
        )

        processor.processNext(10) shouldBe 1
        store.pending shouldContainExactly listOf(unsupported)
        store.processed.single().status shouldBe OutboxStatus.DELIVERED
        store.queriedTypes shouldContainExactly listOf(setOf("kafka"))
    }

    test("starting route rotates and claims at most the batch limit") {
        val store = RoutingStore(listOf(routeEntry("kafka"), routeEntry("http")))
        val deliverer = CompositeMessageDeliverer(listOf(routeDeliverer("kafka"), routeDeliverer("http")))
        val processor = OutboxProcessor(store, OutboxEntryProcessor(deliverer, RetryPolicy(1), Clock.systemUTC()))

        processor.processNext(1) shouldBe 1
        processor.processNext(1) shouldBe 1
        store.queriedTypes shouldContainExactly listOf(setOf("kafka"), setOf("http"))
        store.processed.map { it.deliveryType } shouldContainExactly listOf("kafka", "http")
    }

    test("an empty poll with many routes uses at most two store calls") {
        val store = RoutingStore(emptyList())
        val deliverer = CompositeMessageDeliverer((0 until 100).map { routeDeliverer("route_$it") })
        val processor = OutboxProcessor(store, OutboxEntryProcessor(deliverer, RetryPolicy(1), Clock.systemUTC()))

        processor.processNext(10) shouldBe 0
        store.queriedTypes.size shouldBe 2
        store.queriedTypes.first().size shouldBe 1
        store.queriedTypes.last().size shouldBe 99
    }

    test("processing with a store lacking route-aware claims fails before legacy claim") {
        val store = object : OutboxStore by RoutingStore(emptyList()) {
            override fun claimPending(limit: Int): List<OutboxEntry> = error("legacy claim was called")
        }
        val processor = OutboxProcessor(
            store,
            OutboxEntryProcessor(routeDeliverer("kafka"), RetryPolicy(1), Clock.systemUTC()),
        )

        shouldThrow<IllegalStateException> { processor.processNext() }
    }
})
