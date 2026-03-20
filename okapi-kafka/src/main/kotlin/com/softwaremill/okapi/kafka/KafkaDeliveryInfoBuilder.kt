package com.softwaremill.okapi.kafka

@DslMarker
internal annotation class KafkaDeliveryInfoDsl

@KafkaDeliveryInfoDsl
class KafkaDeliveryInfoBuilder {
    var topic: String = ""
    var partitionKey: String? = null
    private val headers: MutableMap<String, String> = mutableMapOf()

    fun header(key: String, value: String) {
        headers[key] = value
    }

    fun build(): KafkaDeliveryInfo = KafkaDeliveryInfo(
        topic = topic,
        partitionKey = partitionKey,
        headers = headers.toMap(),
    )
}

fun kafkaDeliveryInfo(block: KafkaDeliveryInfoBuilder.() -> Unit): KafkaDeliveryInfo = KafkaDeliveryInfoBuilder().apply(block).build()
