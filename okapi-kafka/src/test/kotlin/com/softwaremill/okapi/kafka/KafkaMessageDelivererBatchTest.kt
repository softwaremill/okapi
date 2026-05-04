package com.softwaremill.okapi.kafka

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.InterruptException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Instant
import java.util.concurrent.Future

private fun entry(suffix: String, metadataOverride: String? = null): OutboxEntry {
    val info = kafkaDeliveryInfo { topic = "topic-$suffix" }
    val baseEntry = OutboxEntry.createPending(OutboxMessage("evt-$suffix", """{"k":"v-$suffix"}"""), info, Instant.now())
    return if (metadataOverride != null) baseEntry.copy(deliveryMetadata = metadataOverride) else baseEntry
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

    test("deliverBatch fires all sends before flushing — single flush call") {
        // MockProducer with autoComplete=false: futures stay pending until completeNext/errorNext,
        // so flush() is the only way settlement can happen — verifies the fire-flush-await sequence.
        var flushCount = 0
        val producer = object : MockProducer<String, String>(false, null, StringSerializer(), StringSerializer()) {
            override fun flush() {
                flushCount++
                while (completeNext()) Unit
            }
        }
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        val results = deliverer.deliverBatch(entries)

        flushCount shouldBe 1
        results.forEach { (_, r) -> r shouldBe DeliveryResult.Success }
    }

    test("deliverBatch maps synchronous PermanentFailure for all entries when sendException is global") {
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

    test("deliverBatch maps synchronous RetriableFailure when send throws RetriableException") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        producer.sendException = NetworkException("broker temporarily unreachable")
        val deliverer = KafkaMessageDeliverer(producer)

        val results = deliverer.deliverBatch(listOf(entry("a")))

        results[0].second.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("deliverBatch with future-based RetriableException classifies as RetriableFailure") {
        // Drive mixed outcomes from inside flush(): entry 0 completes OK, entry 1 errors.
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

    test("deliverBatch handles mixed sync-throw + async outcomes in one batch with positional integrity") {
        // Throw synchronously on the 2nd send only; first goes async-success, third goes async-fail.
        val producer = object : MockProducer<String, String>(false, null, StringSerializer(), StringSerializer()) {
            private var sendCount = 0

            override fun send(record: ProducerRecord<String, String>): Future<org.apache.kafka.clients.producer.RecordMetadata> {
                sendCount++
                if (sendCount == 2) throw AuthenticationException("forbidden on send #$sendCount")
                return super.send(record)
            }

            override fun flush() {
                completeNext() // entry 0 -> Success
                errorNext(NetworkException("async fail")) // entry 2 -> Retriable
            }
        }
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 3
        // Positional integrity: result[i] corresponds to entries[i] regardless of outcome variant
        results.map { it.first } shouldBe entries

        results[0].second shouldBe DeliveryResult.Success
        results[1].second.shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
        (results[1].second as DeliveryResult.PermanentFailure).error shouldContain "forbidden"
        results[2].second.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
        (results[2].second as DeliveryResult.RetriableFailure).error shouldContain "async fail"
    }

    test("deliverBatch poison-pill metadata yields PermanentFailure for bad entry, others unaffected") {
        val producer = MockProducer(true, null, StringSerializer(), StringSerializer())
        val deliverer = KafkaMessageDeliverer(producer)
        val good1 = entry("good1")
        val poisoned = entry("bad", metadataOverride = "{not valid kafka info json}")
        val good2 = entry("good2")

        val results = deliverer.deliverBatch(listOf(good1, poisoned, good2))

        results.size shouldBe 3
        results.map { it.first } shouldBe listOf(good1, poisoned, good2)
        results[0].second shouldBe DeliveryResult.Success
        results[1].second.shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
        results[2].second shouldBe DeliveryResult.Success
        // Only the good entries actually reached the producer
        producer.history().size shouldBe 2
    }

    test("deliverBatch survives flush throwing non-Interrupt exception by classifying per-entry futures") {
        // Flush blows up; each per-entry future has been settled by completeNext/errorNext just before.
        // Contract: deliverBatch never re-throws — it always returns one DeliveryResult per input entry.
        val producer = object : MockProducer<String, String>(false, null, StringSerializer(), StringSerializer()) {
            override fun flush() {
                completeNext()
                errorNext(NetworkException("via future"))
                throw IllegalStateException("producer fatally borked")
            }
        }
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"), entry("b"))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 2
        results[0].second shouldBe DeliveryResult.Success
        results[1].second.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("deliverBatch interrupted during flush re-arms interrupt flag and classifies pending futures as Retriable") {
        // flush() throws Kafka's InterruptException without settling the futures. Our awaitOne
        // then encounters Future.get() on an interrupted thread; for incomplete futures this
        // raises InterruptedException which we explicitly classify as RetriableFailure (so the
        // outbox reschedules instead of marking PermanentFailure).
        val producer = object : MockProducer<String, String>(false, null, StringSerializer(), StringSerializer()) {
            override fun flush() {
                throw InterruptException("interrupted")
            }
        }
        val deliverer = KafkaMessageDeliverer(producer)
        val entries = listOf(entry("a"))

        val results: List<Pair<OutboxEntry, DeliveryResult>>
        try {
            results = deliverer.deliverBatch(entries)
        } finally {
            // Drain the interrupt status so it doesn't leak to the next test (Thread.interrupted clears it).
            Thread.interrupted()
        }

        results.size shouldBe 1
        results[0].second.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }
})
