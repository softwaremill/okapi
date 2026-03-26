package com.softwaremill.okapi.test.e2e

import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.kafka.KafkaMessageDeliverer
import com.softwaremill.okapi.kafka.kafkaDeliveryInfo
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.KafkaTestSupport
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.KafkaProducer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Duration
import java.util.UUID

class KafkaEndToEndTest : FunSpec({
    val db = PostgresTestSupport()
    val kafka = KafkaTestSupport()
    var producer: KafkaProducer<String, String>? = null

    beforeSpec {
        db.start()
        kafka.start()
        producer = kafka.createProducer()
    }

    afterSpec {
        producer?.close()
        kafka.stop()
        db.stop()
    }

    beforeEach {
        db.truncate()
    }

    test("full pipeline: publish to outbox -> processNext -> message on Kafka topic") {
        val clock = Clock.systemUTC()
        val store = PostgresOutboxStore(clock)
        val publisher = OutboxPublisher(store, clock)
        val deliverer = KafkaMessageDeliverer(producer!!)
        val entryProcessor = OutboxEntryProcessor(deliverer, RetryPolicy(maxRetries = 3), clock)
        val processor = OutboxProcessor(store, entryProcessor)

        val topic = "orders-${UUID.randomUUID()}"
        val payload = """{"orderId":"order-42"}"""
        val info = kafkaDeliveryInfo {
            this.topic = topic
            partitionKey = "user-1"
        }

        transaction { publisher.publish(OutboxMessage("order.created", payload), info) }
        transaction { processor.processNext() }

        val counts = transaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.DELIVERED to 1L)

        val consumer = kafka.createConsumer(groupId = "e2e-test-${UUID.randomUUID()}")
        consumer.subscribe(listOf(topic))
        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()

        records.count() shouldBe 1
        val record = records.first()
        record.value() shouldBe payload
        record.key() shouldBe "user-1"
    }
})
