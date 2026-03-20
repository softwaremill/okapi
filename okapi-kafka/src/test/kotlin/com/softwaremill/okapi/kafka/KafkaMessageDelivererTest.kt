package com.softwaremill.okapi.kafka

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.AuthenticationException
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
        val producer = MockProducer(true, StringSerializer(), StringSerializer())
        val deliverer = KafkaMessageDeliverer(producer)
        deliverer.deliver(entry()) shouldBe DeliveryResult.Success
    }

    test("retriable exception (NetworkException) → RetriableFailure") {
        val producer = MockProducer(true, StringSerializer(), StringSerializer())
        producer.sendException = NetworkException("broker down")
        val deliverer = KafkaMessageDeliverer(producer)
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("permanent exception (AuthenticationException) → PermanentFailure") {
        val producer = MockProducer(true, StringSerializer(), StringSerializer())
        producer.sendException = AuthenticationException("bad credentials")
        val deliverer = KafkaMessageDeliverer(producer)
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
    }

    test("permanent exception (RecordTooLargeException) → PermanentFailure") {
        val producer = MockProducer(true, StringSerializer(), StringSerializer())
        producer.sendException = RecordTooLargeException("too big")
        val deliverer = KafkaMessageDeliverer(producer)
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
    }
})
