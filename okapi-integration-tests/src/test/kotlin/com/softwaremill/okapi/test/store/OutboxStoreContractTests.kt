package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

private class StubDeliveryInfo(
    override val type: String = "test",
    private val metadata: String = """{"type":"test"}""",
) : DeliveryInfo {
    override fun serialize(): String = metadata
}

private fun createTestEntry(
    now: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    messageType: String = "order.created",
    payload: String = """{"orderId":"123"}""",
    deliveryInfo: DeliveryInfo = StubDeliveryInfo(),
): OutboxEntry = OutboxEntry.createPending(
    message = OutboxMessage(messageType = messageType, payload = payload),
    deliveryInfo = deliveryInfo,
    now = now,
)

fun FunSpec.outboxStoreContractTests(
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

    test("[$dbName] persist and read back via claimPending") {
        val entry = createTestEntry()

        transaction { store.persist(entry) }

        val claimed = transaction { store.claimPending(10) }

        claimed shouldHaveSize 1
        val found = claimed.first()
        found.outboxId shouldBe entry.outboxId
        found.messageType shouldBe entry.messageType
        found.payload shouldBe entry.payload
        found.status shouldBe OutboxStatus.PENDING
        found.retries shouldBe 0
        found.deliveryType shouldBe "test"
        found.deliveryMetadata.replace(" ", "") shouldBe """{"type":"test"}"""
    }

    test("[$dbName] claimPending returns entries ordered by created_at ASC") {
        val t1 = Instant.parse("2024-01-01T00:00:00Z")
        val t2 = Instant.parse("2024-01-02T00:00:00Z")
        val t3 = Instant.parse("2024-01-03T00:00:00Z")

        // Insert in non-sequential order to verify ordering
        val e2 = createTestEntry(now = t2, messageType = "type.second")
        val e3 = createTestEntry(now = t3, messageType = "type.third")
        val e1 = createTestEntry(now = t1, messageType = "type.first")

        transaction {
            store.persist(e2)
            store.persist(e3)
            store.persist(e1)
        }

        val claimed = transaction { store.claimPending(10) }

        claimed shouldHaveSize 3
        claimed[0].messageType shouldBe "type.first"
        claimed[1].messageType shouldBe "type.second"
        claimed[2].messageType shouldBe "type.third"
    }

    test("[$dbName] claimPending respects limit") {
        transaction {
            repeat(5) { i ->
                val entry = createTestEntry(
                    now = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(i.toLong()),
                    messageType = "type.$i",
                )
                store.persist(entry)
            }
        }

        val claimed = transaction { store.claimPending(2) }

        claimed shouldHaveSize 2
    }

    test("[$dbName] claimPending ignores non-PENDING entries") {
        val pendingEntry = createTestEntry(
            now = Instant.parse("2024-01-01T00:00:00Z"),
            messageType = "type.pending",
        )
        val toBeDelivered = createTestEntry(
            now = Instant.parse("2024-01-01T01:00:00Z"),
            messageType = "type.delivered",
        )

        transaction {
            store.persist(pendingEntry)
            store.persist(toBeDelivered)
        }

        // Claim the second entry and mark it delivered
        transaction {
            val claimed = store.claimPending(10)
            val deliveredCandidate = claimed.first { it.outboxId == toBeDelivered.outboxId }
            store.updateAfterProcessing(deliveredCandidate.toDelivered(Instant.parse("2024-01-02T00:00:00Z")))
        }

        val claimed = transaction { store.claimPending(10) }

        claimed shouldHaveSize 1
        claimed.first().messageType shouldBe "type.pending"
    }

    test("[$dbName] updateAfterProcessing persists status change") {
        val entry = createTestEntry()

        transaction { store.persist(entry) }

        transaction {
            val claimed = store.claimPending(10)
            val delivered = claimed.first().toDelivered(Instant.parse("2024-01-02T00:00:00Z"))
            store.updateAfterProcessing(delivered)
        }

        val counts = transaction { store.countByStatuses() }

        counts shouldContain (OutboxStatus.DELIVERED to 1L)
        counts shouldContain (OutboxStatus.PENDING to 0L)
    }

    test("[$dbName] removeDeliveredBefore deletes old delivered entries") {
        val oldEntry = createTestEntry(
            now = Instant.parse("2024-01-01T00:00:00Z"),
            messageType = "type.old",
        )
        val recentEntry = createTestEntry(
            now = Instant.parse("2024-01-10T00:00:00Z"),
            messageType = "type.recent",
        )

        transaction {
            store.persist(oldEntry)
            store.persist(recentEntry)
        }

        // Mark both as delivered with different lastAttempt timestamps
        transaction {
            val claimed = store.claimPending(10)
            val old = claimed.first { it.outboxId == oldEntry.outboxId }
            val recent = claimed.first { it.outboxId == recentEntry.outboxId }
            store.updateAfterProcessing(old.toDelivered(Instant.parse("2024-01-02T00:00:00Z")))
            store.updateAfterProcessing(recent.toDelivered(Instant.parse("2024-01-11T00:00:00Z")))
        }

        // Remove delivered before Jan 5 — should delete old (lastAttempt=Jan 2) but keep recent (lastAttempt=Jan 11)
        transaction { store.removeDeliveredBefore(Instant.parse("2024-01-05T00:00:00Z"), limit = 100) }

        val counts = transaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.DELIVERED to 1L)
    }

    test("[$dbName] countByStatuses returns correct counts") {
        val pending1 = createTestEntry(
            now = Instant.parse("2024-01-01T00:00:00Z"),
            messageType = "type.pending1",
        )
        val pending2 = createTestEntry(
            now = Instant.parse("2024-01-01T01:00:00Z"),
            messageType = "type.pending2",
        )
        val toDeliver = createTestEntry(
            now = Instant.parse("2024-01-01T02:00:00Z"),
            messageType = "type.delivered",
        )
        val toFail = createTestEntry(
            now = Instant.parse("2024-01-01T03:00:00Z"),
            messageType = "type.failed",
        )

        transaction {
            store.persist(pending1)
            store.persist(pending2)
            store.persist(toDeliver)
            store.persist(toFail)
        }

        transaction {
            val claimed = store.claimPending(10)
            val deliverEntry = claimed.first { it.outboxId == toDeliver.outboxId }
            val failEntry = claimed.first { it.outboxId == toFail.outboxId }
            store.updateAfterProcessing(deliverEntry.toDelivered(Instant.parse("2024-01-02T00:00:00Z")))
            store.updateAfterProcessing(failEntry.toFailed(Instant.parse("2024-01-02T00:00:00Z"), "some error"))
        }

        val counts = transaction { store.countByStatuses() }

        counts shouldContain (OutboxStatus.PENDING to 2L)
        counts shouldContain (OutboxStatus.DELIVERED to 1L)
        counts shouldContain (OutboxStatus.FAILED to 1L)
    }

    test("[$dbName] claimPending returns empty when no PENDING entries") {
        val claimed = transaction { store.claimPending(10) }

        claimed shouldHaveSize 0
    }
}
