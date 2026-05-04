package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

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
        results.map { it.first } shouldBe entries
        results[0].second shouldBe DeliveryResult.Success
        results[1].second shouldBe DeliveryResult.RetriableFailure("503")
        results[2].second shouldBe DeliveryResult.Success
        results[3].second shouldBe DeliveryResult.RetriableFailure("503")
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
        results[0].second shouldBe DeliveryResult.Success
        results[1].second.shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
        (results[1].second as DeliveryResult.PermanentFailure).error shouldContain "missing"
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
            override fun deliverBatch(entries: List<OutboxEntry>): List<Pair<OutboxEntry, DeliveryResult>> {
                batchCallsKafka++
                return entries.map { it to DeliveryResult.Success }
            }
        }
        val httpDeliverer = object : MessageDeliverer {
            override val type = "http"
            override fun deliver(entry: OutboxEntry): DeliveryResult = DeliveryResult.Success
            override fun deliverBatch(entries: List<OutboxEntry>): List<Pair<OutboxEntry, DeliveryResult>> {
                batchCallsHttp++
                return entries.map { it to DeliveryResult.Success }
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
})
