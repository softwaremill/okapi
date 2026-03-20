package com.softwaremill.okapi.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class HttpDeliveryInfoTest : BehaviorSpec({
    Given("HttpDeliveryInfo serialization") {
        val info =
            HttpDeliveryInfo(
                serviceName = "order-service",
                endpointPath = "/api/orders",
                httpMethod = HttpMethod.POST,
                headers = mapOf("X-Trace-Id" to "abc-123"),
            )

        When("serialize() is called") {
            val json = info.serialize()

            Then("JSON contains all fields") {
                json shouldContain "order-service"
                json shouldContain "/api/orders"
                json shouldContain "POST"
                json shouldContain "X-Trace-Id"
            }
        }

        When("deserialize() is called with the serialized JSON") {
            val roundTrip = HttpDeliveryInfo.deserialize(info.serialize())

            then("round-trip restores all fields") {
                roundTrip.serviceName shouldBe info.serviceName
                roundTrip.endpointPath shouldBe info.endpointPath
                roundTrip.httpMethod shouldBe info.httpMethod
                roundTrip.headers shouldContain ("X-Trace-Id" to "abc-123")
            }
        }
    }

    Given("HttpDeliveryInfoBuilder DSL") {
        When("all fields are set") {
            val info =
                httpDeliveryInfo {
                    serviceName = "payment-service"
                    endpointPath = "/api/payments"
                    httpMethod = HttpMethod.PUT
                    header("Authorization", "Bearer token")
                }

            Then("builds HttpDeliveryInfo with correct values") {
                info.serviceName shouldBe "payment-service"
                info.endpointPath shouldBe "/api/payments"
                info.httpMethod shouldBe HttpMethod.PUT
                info.headers shouldContain ("Authorization" to "Bearer token")
            }
        }

        When("httpMethod is not set") {
            val info =
                httpDeliveryInfo {
                    serviceName = "some-service"
                    endpointPath = "/api/resource"
                }

            Then("defaults to POST") {
                info.httpMethod shouldBe HttpMethod.POST
            }
        }
    }

    Given("HttpDeliveryInfo validation") {
        When("serviceName is blank") {
            Then("throws IllegalArgumentException") {
                shouldThrow<IllegalArgumentException> {
                    HttpDeliveryInfo(
                        serviceName = "  ",
                        endpointPath = "/api",
                        httpMethod = HttpMethod.POST,
                    )
                }
            }
        }

        When("endpointPath is blank") {
            Then("throws IllegalArgumentException") {
                shouldThrow<IllegalArgumentException> {
                    HttpDeliveryInfo(
                        serviceName = "svc",
                        endpointPath = "",
                        httpMethod = HttpMethod.POST,
                    )
                }
            }
        }
    }
})
