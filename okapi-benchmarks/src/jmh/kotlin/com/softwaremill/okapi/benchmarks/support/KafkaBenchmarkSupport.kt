package com.softwaremill.okapi.benchmarks.support

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.kafka.KafkaContainer

class KafkaBenchmarkSupport {
    private val container = KafkaContainer("apache/kafka:3.8.1")

    fun start() {
        container.start()
    }

    fun stop() {
        container.stop()
    }

    fun createProducer(): KafkaProducer<String, String> = KafkaProducer(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to container.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.ACKS_CONFIG to "all",
        ),
    )
}
