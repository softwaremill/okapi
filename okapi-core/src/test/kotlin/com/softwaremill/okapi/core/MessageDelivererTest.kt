package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private val stubDeliveryInfo = object : DeliveryInfo {
    override val type = "stub"
    override fun serialize(): String = """{"type":"stub"}"""
}

private fun stubEntry(id: Int) = OutboxEntry.createPending(OutboxMessage("evt-$id", "{}"), stubDeliveryInfo, Instant.EPOCH)

private fun delivererReturning(vararg results: DeliveryResult) = object : MessageDeliverer {
    override val type = "stub"
    private var idx = 0
    override fun deliver(entry: OutboxEntry): DeliveryResult = results[idx++]
}

class MessageDelivererTest : FunSpec({
    test("default deliverBatch delegates to deliver and preserves input order") {
        val deliverer = delivererReturning(
            DeliveryResult.Success,
            DeliveryResult.RetriableFailure("err1"),
            DeliveryResult.PermanentFailure("err2"),
        )
        val entries = listOf(stubEntry(1), stubEntry(2), stubEntry(3))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 3
        results[0].first shouldBe entries[0]
        results[0].second shouldBe DeliveryResult.Success
        results[1].first shouldBe entries[1]
        results[1].second shouldBe DeliveryResult.RetriableFailure("err1")
        results[2].first shouldBe entries[2]
        results[2].second shouldBe DeliveryResult.PermanentFailure("err2")
    }

    test("default deliverBatch on empty input returns empty list without calling deliver") {
        var deliverCalls = 0
        val deliverer = object : MessageDeliverer {
            override val type = "stub"
            override fun deliver(entry: OutboxEntry): DeliveryResult {
                deliverCalls++
                return DeliveryResult.Success
            }
        }

        deliverer.deliverBatch(emptyList()) shouldBe emptyList()
        deliverCalls shouldBe 0
    }
})
