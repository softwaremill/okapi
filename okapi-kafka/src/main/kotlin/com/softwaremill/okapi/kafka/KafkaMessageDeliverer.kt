package com.softwaremill.okapi.kafka

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.errors.InterruptException
import org.apache.kafka.common.errors.RetriableException
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * [MessageDeliverer] that publishes outbox entries to Kafka topics.
 *
 * Kafka [RetriableException]s map to [DeliveryResult.RetriableFailure];
 * all other errors map to [DeliveryResult.PermanentFailure].
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
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        DeliveryResult.RetriableFailure(e.message ?: e.javaClass.simpleName)
    } catch (e: Exception) {
        classifyException(e)
    }

    /**
     * Uses fire-flush-await: send all entries, then a single `flush()` (which
     * bypasses `linger.ms`), then collect outcomes via non-blocking `Future.get()`
     * since completion is already settled. A failing `send()` does not abort the
     * batch; the result list mirrors input order.
     *
     * If `flush()` itself fails (interrupt, fatal producer state), per-entry
     * futures still surface their own exception via `get()` and are classified
     * individually — the batch as a whole is never abandoned.
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
            logger.warn("Kafka producer.flush() interrupted; per-entry futures will surface the cause", e)
        } catch (e: Exception) {
            logger.warn("Kafka producer.flush() failed for batch of {}; classifying per-entry from future state", entries.size, e)
        }

        return inflight.map { (entry, outcome) -> entry to awaitOne(outcome) }
    }

    private fun fireOne(entry: OutboxEntry): SendOutcome = try {
        SendOutcome.Sent(producer.send(buildRecord(entry)))
    } catch (e: Exception) {
        val classified = classifyException(e)
        logger.debug("Kafka send rejected synchronously for entry {}: {}", entry.outboxId, e.toString())
        SendOutcome.ImmediateFailure(classified)
    }

    private fun awaitOne(outcome: SendOutcome): DeliveryResult = when (outcome) {
        is SendOutcome.ImmediateFailure -> outcome.result
        is SendOutcome.Sent -> try {
            outcome.future.get()
            DeliveryResult.Success
        } catch (e: ExecutionException) {
            classifyException(e.cause ?: e)
        } catch (e: InterruptedException) {
            // Thread was interrupted while waiting for an in-flight future. The interrupt may have
            // come from flush() (already restored the flag) or from an outer cancellation; either
            // way, the entry is unsent — retry semantics, not a poison pill.
            Thread.currentThread().interrupt()
            DeliveryResult.RetriableFailure(e.message ?: e.javaClass.simpleName)
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

    private fun classifyException(e: Throwable): DeliveryResult {
        val message = e.message ?: e.javaClass.simpleName
        return if (e is RetriableException) {
            DeliveryResult.RetriableFailure(message)
        } else {
            DeliveryResult.PermanentFailure(message)
        }
    }

    private sealed interface SendOutcome {
        @JvmInline
        value class Sent(val future: Future<RecordMetadata>) : SendOutcome
        data class ImmediateFailure(val result: DeliveryResult) : SendOutcome
    }

    companion object {
        private val logger = LoggerFactory.getLogger(KafkaMessageDeliverer::class.java)
    }
}
