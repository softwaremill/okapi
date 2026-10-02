package com.softwaremill.okapi.test.transport

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxHeaders
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
    lateinit var producer: KafkaProducer<String, String>
    lateinit var deliverer: KafkaMessageDeliverer

    beforeSpec {
        kafka.start()
        producer = kafka.createProducer()
        deliverer = KafkaMessageDeliverer(producer)
    }

    afterSpec {
        producer.close()
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

    test("consumer reads x-outbox-id from the broker and it matches the entry UUID") {
        val entry = entryWithInfo(
            topic = "outbox-id-topic-${UUID.randomUUID()}",
            headers = mapOf("traceId" to "trace-abc"),
        )
        deliverer.deliver(entry) shouldBe DeliveryResult.Success

        val consumer = kafka.createConsumer(groupId = "test-outbox-id-${UUID.randomUUID()}")
        consumer.subscribe(listOf(KafkaDeliveryInfo.deserialize(entry.deliveryMetadata).topic))
        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()

        records.count() shouldBe 1
        val record = records.first()
        // lastHeader is the documented way for consumers to read it.
        String(record.headers().lastHeader(OutboxHeaders.OUTBOX_ID).value()) shouldBe entry.outboxId.raw.toString()
        // Caller headers still survive alongside it.
        record.headers().associate { it.key() to String(it.value()) }["traceId"] shouldBe "trace-abc"
    }

    test("redelivering the same entry carries the same x-outbox-id, which is what makes dedup work") {
        val entry = entryWithInfo(topic = "outbox-id-retry-topic-${UUID.randomUUID()}")
        deliverer.deliver(entry) shouldBe DeliveryResult.Success
        deliverer.deliver(entry) shouldBe DeliveryResult.Success

        val consumer = kafka.createConsumer(groupId = "test-outbox-id-retry-${UUID.randomUUID()}")
        consumer.subscribe(listOf(KafkaDeliveryInfo.deserialize(entry.deliveryMetadata).topic))
        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()

        records.count() shouldBe 2
        records.map { String(it.headers().lastHeader(OutboxHeaders.OUTBOX_ID).value()) }.toSet() shouldBe
            setOf(entry.outboxId.raw.toString())
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

    test("deliverBatch sends all entries to topic and returns Success in input order") {
        val topic = "batch-topic-${UUID.randomUUID()}"
        val entries = (0 until 25).map { i ->
            entryWithInfo(topic = topic, payload = """{"seq":$i}""")
        }

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe entries.size
        results.forEachIndexed { i, (entry, result) ->
            entry.outboxId shouldBe entries[i].outboxId
            result shouldBe DeliveryResult.Success
        }

        val consumer = kafka.createConsumer(groupId = "test-batch-${UUID.randomUUID()}")
        consumer.subscribe(listOf(topic))
        val received = mutableListOf<String>()
        val deadline = Instant.now().plusSeconds(15)
        while (received.size < entries.size && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofSeconds(2)).forEach { received.add(it.value()) }
        }
        consumer.close()

        received.size shouldBe entries.size
    }

    test("deliverBatch on empty input returns empty list without contacting broker") {
        val results = deliverer.deliverBatch(emptyList())
        results.size shouldBe 0
    }
})
