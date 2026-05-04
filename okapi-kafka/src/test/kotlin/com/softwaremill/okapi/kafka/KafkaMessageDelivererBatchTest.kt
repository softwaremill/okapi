package com.softwaremill.okapi.kafka

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Instant

private fun entry(suffix: String): OutboxEntry {
    val info = kafkaDeliveryInfo { topic = "topic-$suffix" }
    return OutboxEntry.createPending(OutboxMessage("evt-$suffix", """{"k":"v-$suffix"}"""), info, Instant.now())
}

class KafkaMessageDelivererBatchTest : FunSpec({
    test("deliverBatch on empty input returns empty list and does not invoke producer") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        val deliverer = KafkaMessageDeliverer(producer)

        deliverer.deliverBatch(emptyList()) shouldBe emptyList()
        producer.history().size shouldBe 0
    }

    test("deliverBatch with all-success preserves input order and reports all entries delivered") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 3
        results.map { it.first } shouldBe entries
        results.forEach { (_, r) -> r shouldBe DeliveryResult.Success }
        producer.history().size shouldBe 3
    }

    test("deliverBatch fires all sends BEFORE flushing — flush count incremented exactly once") {
        // Drives the producer in non-auto mode: futures are pending until completeNext/errorNext,
        // and flush() will complete them. Verifies the fire-flush-await sequence:
        // fire 3 sends -> flush completes them in one shot -> get() returns Success for all.
        var flushCount = 0
        val producer = object : MockProducer<String, String>(false, null, StringSerializer(), StringSerializer()) {
            override fun flush() {
                flushCount++
                while (completeNext()) {
                    // drain remaining
                }
            }
        }
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        val results = deliverer.deliverBatch(entries)

        flushCount shouldBe 1
        results.forEach { (_, r) -> r shouldBe DeliveryResult.Success }
    }

    test("deliverBatch maps synchronous send exception to PermanentFailure for ALL entries (sendException is global)") {
        // MockProducer.sendException makes producer.send() throw synchronously for every call.
        // Each entry hits the fire-phase try/catch and gets classified individually.
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        producer.sendException = AuthenticationException("bad creds")
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"), entry("b"))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 2
        results.forEach { (_, r) ->
            r.shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
            (r as DeliveryResult.PermanentFailure).error shouldContain "bad creds"
        }
    }

    test("deliverBatch maps synchronous retriable exception to RetriableFailure") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        producer.sendException = NetworkException("broker temporarily unreachable")
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 1
        results[0].second.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("deliverBatch with future-based RetriableException classifies as RetriableFailure") {
        // Drive mixed outcomes from inside flush(): first send completes OK, second errors.
        // This simulates the Future-based failure path (vs synchronous send throw, covered above)
        // and exercises awaitOne's ExecutionException unwrap.
        val producer = object : MockProducer<String, String>(false, null, StringSerializer(), StringSerializer()) {
            override fun flush() {
                completeNext()
                errorNext(NetworkException("transient"))
            }
        }
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"), entry("b"))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 2
        results[0].second shouldBe DeliveryResult.Success
        results[1].second.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
        (results[1].second as DeliveryResult.RetriableFailure).error shouldContain "transient"
    }
})
