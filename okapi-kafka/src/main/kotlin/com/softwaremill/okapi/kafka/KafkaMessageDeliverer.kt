package com.softwaremill.okapi.kafka

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
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
            DeliveryResult.RetriableFailure(e.cause?.message ?: "Send failed")
        } catch (e: Exception) {
            DeliveryResult.RetriableFailure(e.message ?: "Unknown error")
        }
    }
}
