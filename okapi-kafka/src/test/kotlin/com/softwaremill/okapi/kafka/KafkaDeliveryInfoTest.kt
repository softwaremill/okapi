package com.softwaremill.okapi.kafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class KafkaDeliveryInfoTest :
    BehaviorSpec({
        given("KafkaDeliveryInfo serialization") {
            val info =
                KafkaDeliveryInfo(
                    topic = "order-events",
                    partitionKey = "order-42",
                    headers = mapOf("X-Source" to "outbox"),
                )

            `when`("serialize() is called") {
                val json = info.serialize()

                then("JSON contains all fields") {
                    json shouldContain "order-events"
                    json shouldContain "order-42"
                    json shouldContain "X-Source"
                }
            }

            `when`("deserialize() is called with the serialized JSON") {
                val roundTrip = KafkaDeliveryInfo.deserialize(info.serialize())

                then("round-trip restores all fields") {
                    roundTrip.topic shouldBe info.topic
                    roundTrip.partitionKey shouldBe info.partitionKey
                    roundTrip.headers shouldContain ("X-Source" to "outbox")
                }
            }
        }

        given("KafkaDeliveryInfo without partitionKey") {
            val info = KafkaDeliveryInfo(topic = "events")

            `when`("serialized and deserialized") {
                val roundTrip = KafkaDeliveryInfo.deserialize(info.serialize())

                then("partitionKey remains null") {
                    roundTrip.partitionKey.shouldBeNull()
                }
            }
        }

        given("KafkaDeliveryInfoBuilder DSL") {
            `when`("all fields are set") {
                val info =
                    kafkaDeliveryInfo {
                        topic = "payment-events"
                        partitionKey = "payment-99"
                        header("X-Trace-Id", "trace-abc")
                    }

                then("builds KafkaDeliveryInfo with correct values") {
                    info.topic shouldBe "payment-events"
                    info.partitionKey shouldBe "payment-99"
                    info.headers shouldContain ("X-Trace-Id" to "trace-abc")
                }
            }

            `when`("only topic is set") {
                val info = kafkaDeliveryInfo { topic = "minimal-topic" }

                then("partitionKey defaults to null and headers are empty") {
                    info.partitionKey.shouldBeNull()
                    info.headers shouldBe emptyMap()
                }
            }
        }

        given("KafkaDeliveryInfo validation") {
            `when`("topic is blank") {
                then("throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        KafkaDeliveryInfo(topic = "  ")
                    }
                }
            }
        }
    })
