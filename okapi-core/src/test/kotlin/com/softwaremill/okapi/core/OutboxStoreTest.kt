package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private val stubDeliveryInfo = object : DeliveryInfo {
    override val type = "stub"

    override fun serialize(): String = """{"type":"stub"}"""
}

private fun stubEntry(messageType: String = "test.event"): OutboxEntry =
    OutboxEntry.createPending(OutboxMessage(messageType, "{}"), stubDeliveryInfo, Instant.EPOCH)

class OutboxStoreTest : FunSpec({

    test("default updateAfterProcessingBatch loops over updateAfterProcessing for each entry, in order") {
        val updateCalls = mutableListOf<OutboxEntry>()
        val store = object : OutboxStore {
            override fun persist(entry: OutboxEntry) = entry
            override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
            override fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry = entry.also { updateCalls += it }
            override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
            override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
            override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
        }

        val entries = listOf(stubEntry("a"), stubEntry("b"), stubEntry("c"))
        val result = store.updateAfterProcessingBatch(entries)

        updateCalls shouldBe entries
        result shouldBe entries
    }

    test("default updateAfterProcessingBatch on an empty list calls updateAfterProcessing zero times") {
        val updateCalls = mutableListOf<OutboxEntry>()
        val store = object : OutboxStore {
            override fun persist(entry: OutboxEntry) = entry
            override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
            override fun updateAfterProcessing(entry: OutboxEntry): OutboxEntry = entry.also { updateCalls += it }
            override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
            override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
            override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
        }

        val result = store.updateAfterProcessingBatch(emptyList())

        updateCalls shouldBe emptyList()
        result shouldBe emptyList()
    }
})
