package com.softwaremill.okapi.test.transport

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.kafka.KafkaDeliveryInfo
import com.softwaremill.okapi.kafka.KafkaMessageDeliverer
import com.softwaremill.okapi.kafka.kafkaDeliveryInfo
import com.softwaremill.okapi.test.support.KafkaTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.KafkaProducer
import java.time.Duration
import java.time.Instant
import java.util.UUID

class KafkaTransportIntegrationTest : FunSpec({
    val kafka = KafkaTestSupport()
    var producer: KafkaProducer<String, String>? = null
    lateinit var deliverer: KafkaMessageDeliverer

    beforeSpec {
        kafka.start()
        producer = kafka.createProducer()
        deliverer = KafkaMessageDeliverer(producer!!)
    }

    afterSpec {
        producer?.close()
        kafka.stop()
    }

    fun entryWithInfo(
        topic: String,
        payload: String = """{"orderId":"abc-123"}""",
        partitionKey: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): OutboxEntry {
        val info = kafkaDeliveryInfo {
            this.topic = topic
            this.partitionKey = partitionKey
            headers.forEach { (k, v) -> header(k, v) }
        }
        return OutboxEntry.createPending(
            message = OutboxMessage(messageType = "test.event", payload = payload),
            deliveryInfo = info,
            now = Instant.now(),
        )
    }

    test("deliver sends message to correct topic") {
        val entry = entryWithInfo(topic = "orders")
        deliverer.deliver(entry)

        val consumer = kafka.createConsumer(groupId = "test-topic-${UUID.randomUUID()}")
        consumer.subscribe(listOf("orders"))
        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()

        records.count() shouldBe 1
        val record = records.first()
        record.topic() shouldBe "orders"
        record.value() shouldBe """{"orderId":"abc-123"}"""
    }

    test("deliver preserves headers") {
        val entry = entryWithInfo(
            topic = "header-topic-${UUID.randomUUID()}",
            headers = mapOf("traceId" to "trace-abc", "source" to "okapi"),
        )
        deliverer.deliver(entry)

        val consumer = kafka.createConsumer(groupId = "test-headers-${UUID.randomUUID()}")
        consumer.subscribe(listOf(entry.let { KafkaDeliveryInfo.deserialize(it.deliveryMetadata).topic }))
        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()

        records.count() shouldBe 1
        val record = records.first()
        val headerMap = record.headers().associate { it.key() to String(it.value()) }
        headerMap["traceId"] shouldBe "trace-abc"
        headerMap["source"] shouldBe "okapi"
    }

    test("deliver uses partition key") {
        val entry = entryWithInfo(
            topic = "key-topic-${UUID.randomUUID()}",
            partitionKey = "user-42",
        )
        deliverer.deliver(entry)

        val consumer = kafka.createConsumer(groupId = "test-key-${UUID.randomUUID()}")
        consumer.subscribe(listOf(entry.let { KafkaDeliveryInfo.deserialize(it.deliveryMetadata).topic }))
        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()

        records.count() shouldBe 1
        records.first().key() shouldBe "user-42"
    }

    test("deliver without partition key sends null key") {
        val entry = entryWithInfo(
            topic = "nullkey-topic-${UUID.randomUUID()}",
            partitionKey = null,
        )
        deliverer.deliver(entry)

        val consumer = kafka.createConsumer(groupId = "test-nullkey-${UUID.randomUUID()}")
        consumer.subscribe(listOf(entry.let { KafkaDeliveryInfo.deserialize(it.deliveryMetadata).topic }))
        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()

        records.count() shouldBe 1
        records.first().key().shouldBeNull()
    }

    test("deliver returns Success on successful send") {
        val entry = entryWithInfo(topic = "success-topic-${UUID.randomUUID()}")
        val result = deliverer.deliver(entry)

        result shouldBe DeliveryResult.Success
    }
})
