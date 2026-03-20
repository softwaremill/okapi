package com.softwaremill.okapi.kafka

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.RetriableException
import java.util.concurrent.ExecutionException

class KafkaMessageDeliverer(
    private val producer: Producer<String, String>,
) : MessageDeliverer {
    override val type: String = KafkaDeliveryInfo.TYPE

    override fun deliver(entry: OutboxEntry): DeliveryResult {
        val info = KafkaDeliveryInfo.deserialize(entry.deliveryMetadata)
        val record =
            ProducerRecord(info.topic, info.partitionKey, entry.payload).apply {
                info.headers.forEach { (k, v) -> headers().add(k, v.toByteArray()) }
            }

        return try {
            producer.send(record).get()
            DeliveryResult.Success
        } catch (e: ExecutionException) {
            classifyException(e.cause ?: e)
        } catch (e: Exception) {
            classifyException(e)
        }
    }

    private fun classifyException(e: Throwable): DeliveryResult = if (e is RetriableException) {
        DeliveryResult.RetriableFailure(e.message ?: "Retriable Kafka error")
    } else {
        DeliveryResult.PermanentFailure(e.message ?: "Permanent Kafka error")
    }
}
