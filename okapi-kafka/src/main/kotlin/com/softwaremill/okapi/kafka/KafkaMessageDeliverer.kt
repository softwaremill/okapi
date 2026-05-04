package com.softwaremill.okapi.kafka

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.errors.InterruptException
import org.apache.kafka.common.errors.RetriableException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * [MessageDeliverer] that publishes outbox entries to Kafka topics.
 *
 * - Single-entry [deliver] uses a synchronous send-and-wait round trip.
 * - [deliverBatch] uses **fire-flush-await**: all entries are enqueued via
 *   `producer.send()` (non-blocking), then a single `producer.flush()` call
 *   drives them out in one batched network round trip, then per-entry
 *   `Future.get()` collects results without further blocking.
 *
 * Kafka [RetriableException]s (including [InterruptException] and
 * `BufferExhaustedException`/`TimeoutException` since Kafka 3.0) map to
 * [DeliveryResult.RetriableFailure]; all other errors map to
 * [DeliveryResult.PermanentFailure].
 */
class KafkaMessageDeliverer(
    private val producer: Producer<String, String>,
) : MessageDeliverer {
    override val type: String = KafkaDeliveryInfo.TYPE

    override fun deliver(entry: OutboxEntry): DeliveryResult = try {
        producer.send(buildRecord(entry)).get()
        DeliveryResult.Success
    } catch (e: ExecutionException) {
        classifyException(e.cause ?: e)
    } catch (e: Exception) {
        classifyException(e)
    }

    /**
     * Sends all entries in a fire-flush-await pattern:
     *
     * 1. **Fire** — call `producer.send()` for every entry, capturing either the
     *    in-flight `Future` or a synchronous exception (e.g. `BufferExhaustedException`,
     *    `SerializationException`). One failing send does not abort the batch.
     * 2. **Flush** — `producer.flush()` waits for all in-flight records to be
     *    acknowledged or fail, in a single call regardless of `linger.ms`.
     * 3. **Await** — call `Future.get()` per entry to read the outcome. After
     *    `flush()` these calls are non-blocking — completion is already settled.
     *
     * Per-entry classification preserved; the result list mirrors the input order.
     * If the calling thread is interrupted during `flush()`, the interrupt flag is
     * restored and processing continues — incomplete futures will surface as
     * `RetriableFailure` via `InterruptException` from `get()`.
     */
    override fun deliverBatch(entries: List<OutboxEntry>): List<Pair<OutboxEntry, DeliveryResult>> {
        if (entries.isEmpty()) return emptyList()

        val inflight: List<Pair<OutboxEntry, SendOutcome>> = entries.map { entry ->
            entry to fireOne(entry)
        }

        try {
            producer.flush()
        } catch (e: InterruptException) {
            Thread.currentThread().interrupt()
        }

        return inflight.map { (entry, outcome) -> entry to awaitOne(outcome) }
    }

    private fun fireOne(entry: OutboxEntry): SendOutcome = try {
        SendOutcome.Sent(producer.send(buildRecord(entry)))
    } catch (e: Exception) {
        SendOutcome.ImmediateFailure(classifyException(e))
    }

    private fun awaitOne(outcome: SendOutcome): DeliveryResult = when (outcome) {
        is SendOutcome.ImmediateFailure -> outcome.result
        is SendOutcome.Sent -> try {
            outcome.future.get()
            DeliveryResult.Success
        } catch (e: ExecutionException) {
            classifyException(e.cause ?: e)
        } catch (e: Exception) {
            classifyException(e)
        }
    }

    private fun buildRecord(entry: OutboxEntry): ProducerRecord<String?, String> {
        val info = KafkaDeliveryInfo.deserialize(entry.deliveryMetadata)
        return ProducerRecord<String?, String>(info.topic, info.partitionKey, entry.payload).apply {
            info.headers.forEach { (k, v) -> headers().add(k, v.toByteArray()) }
        }
    }

    private fun classifyException(e: Throwable): DeliveryResult = if (e is RetriableException) {
        DeliveryResult.RetriableFailure(e.message ?: "Retriable Kafka error")
    } else {
        DeliveryResult.PermanentFailure(e.message ?: "Permanent Kafka error")
    }

    private sealed interface SendOutcome {
        data class Sent(val future: Future<RecordMetadata>) : SendOutcome
        data class ImmediateFailure(val result: DeliveryResult) : SendOutcome
    }
}
