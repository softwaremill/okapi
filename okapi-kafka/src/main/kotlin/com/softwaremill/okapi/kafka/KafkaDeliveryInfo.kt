package com.softwaremill.okapi.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.softwaremill.okapi.core.DeliveryInfo

/**
 * Delivery metadata for Kafka topic transport.
 *
 * [topic] is required. Optional [partitionKey] controls partition routing.
 * Custom [headers] are sent as UTF-8 encoded Kafka record headers.
 */
data class KafkaDeliveryInfo(
    override val type: String = TYPE,
    val topic: String,
    val partitionKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
) : DeliveryInfo {
    init {
        require(topic.isNotBlank()) { "topic must not be blank" }
    }

    override fun serialize(): String = mapper.writeValueAsString(this)

    companion object {
        const val TYPE = "kafka"
        private val mapper = jacksonObjectMapper()

        /** Deserializes from JSON stored in [OutboxEntry.deliveryMetadata]. */
        fun deserialize(json: String): KafkaDeliveryInfo = mapper.readValue(json)
    }
}
