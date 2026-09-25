package com.softwaremill.okapi.kafka

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxHeaders
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.InterruptException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Instant

class KafkaMessageDelivererTest : FunSpec({
    fun entry(): OutboxEntry {
        val info = kafkaDeliveryInfo { topic = "test-topic" }
        return OutboxEntry.createPending(OutboxMessage("test", """{"k":"v"}"""), info, Instant.now())
    }

    test("successful send → Success") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        val deliverer = KafkaMessageDeliverer(producer)
        deliverer.deliver(entry()) shouldBe DeliveryResult.Success
    }

    test("retriable exception (NetworkException) → RetriableFailure") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        producer.sendException = NetworkException("broker down")
        val deliverer = KafkaMessageDeliverer(producer)
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("permanent exception (AuthenticationException) → PermanentFailure") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        producer.sendException = AuthenticationException("bad credentials")
        val deliverer = KafkaMessageDeliverer(producer)
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
    }

    test("permanent exception (RecordTooLargeException) → PermanentFailure") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        producer.sendException = RecordTooLargeException("too big")
        val deliverer = KafkaMessageDeliverer(producer)
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
    }

    test("Kafka InterruptException on send → RetriableFailure (interrupt flag restored)") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        producer.sendException = InterruptException("interrupted")
        val deliverer = KafkaMessageDeliverer(producer)
        try {
            deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
            Thread.currentThread().isInterrupted shouldBe true
        } finally {
            // Clear the interrupt flag so it doesn't leak to subsequent tests.
            Thread.interrupted()
        }
    }

    test("Kafka InterruptException on send in deliverBatch → RetriableFailure per entry") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        producer.sendException = InterruptException("interrupted")
        val deliverer = KafkaMessageDeliverer(producer)
        try {
            val results = deliverer.deliverBatch(listOf(entry(), entry()))
            results.size shouldBe 2
            results.forEach { (_, result) ->
                result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
            }
            Thread.currentThread().isInterrupted shouldBe true
        } finally {
            Thread.interrupted()
        }
    }

    test("deliver attaches x-outbox-id carrying the entry's UUID") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        val deliverer = KafkaMessageDeliverer(producer)
        val entry = entry()

        deliverer.deliver(entry)

        val record = producer.history().single()
        String(record.headers().lastHeader(OutboxHeaders.OUTBOX_ID).value()) shouldBe entry.outboxId.raw.toString()
    }

    test("deliverBatch attaches x-outbox-id to every record, each carrying its own entry's UUID") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry(), entry(), entry())

        deliverer.deliverBatch(entries)

        val sentIds = producer.history().map { String(it.headers().lastHeader(OutboxHeaders.OUTBOX_ID).value()) }
        sentIds shouldBe entries.map { it.outboxId.raw.toString() }
    }

    test("okapi's x-outbox-id is last, so it wins over a caller-supplied header of the same name") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        val deliverer = KafkaMessageDeliverer(producer)
        val info = kafkaDeliveryInfo {
            topic = "test-topic"
            header(OutboxHeaders.OUTBOX_ID, "caller-supplied")
        }
        val entry = OutboxEntry.createPending(OutboxMessage("test", """{"k":"v"}"""), info, Instant.now())

        deliverer.deliver(entry)

        val headers = producer.history().single().headers()
        String(headers.lastHeader(OutboxHeaders.OUTBOX_ID).value()) shouldBe entry.outboxId.raw.toString()
        // Kafka headers are multi-valued: the caller's value is shadowed, not dropped.
        headers.headers(OutboxHeaders.OUTBOX_ID).map { String(it.value()) } shouldBe
            listOf("caller-supplied", entry.outboxId.raw.toString())
    }
})
