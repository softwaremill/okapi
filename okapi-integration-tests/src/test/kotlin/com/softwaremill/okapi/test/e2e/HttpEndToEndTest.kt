package com.softwaremill.okapi.test.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.http.HttpMessageDeliverer
import com.softwaremill.okapi.http.ServiceUrlResolver
import com.softwaremill.okapi.http.httpDeliveryInfo
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import java.time.Clock

class HttpEndToEndTest : FunSpec({
    val db = PostgresTestSupport()
    val wiremock = WireMockServer(wireMockConfig().dynamicPort())

    beforeSpec {
        db.start()
        wiremock.start()
    }

    afterSpec {
        wiremock.stop()
        db.stop()
    }

    beforeEach {
        wiremock.resetAll()
        db.truncate()
    }

    fun buildPipeline(maxRetries: Int = 3): Triple<OutboxPublisher, OutboxProcessor, PostgresOutboxStore> {
        val clock = Clock.systemUTC()
        val store = PostgresOutboxStore(db.jdbc, clock)
        val publisher = OutboxPublisher(store, clock)
        val urlResolver = ServiceUrlResolver { "http://localhost:${wiremock.port()}" }
        val entryProcessor = OutboxEntryProcessor(
            HttpMessageDeliverer(urlResolver),
            RetryPolicy(maxRetries = maxRetries),
            clock,
        )
        return Triple(publisher, OutboxProcessor(store, entryProcessor), store)
    }

    fun deliveryInfo() = httpDeliveryInfo {
        serviceName = "notification-service"
        endpointPath = "/api/notify"
    }

    test("HTTP 200 - message delivered, payload matches") {
        val (publisher, processor, store) = buildPipeline()
        val payload = """{"orderId":"abc-123"}"""

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withStatus(200)),
        )

        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", payload), deliveryInfo()) }
        db.jdbc.withTransaction { processor.processNext() }

        wiremock.verify(
            postRequestedFor(urlEqualTo("/api/notify"))
                .withRequestBody(equalTo(payload)),
        )

        val counts = db.jdbc.withTransaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.DELIVERED to 1L)
    }

    test("HTTP 500 - retriable failure, stays PENDING") {
        val (publisher, processor, store) = buildPipeline()

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withStatus(500)),
        )

        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", """{"id":"1"}"""), deliveryInfo()) }
        db.jdbc.withTransaction { processor.processNext() }

        val counts = db.jdbc.withTransaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.PENDING to 1L)
        counts shouldContain (OutboxStatus.DELIVERED to 0L)
    }

    test("HTTP 400 - permanent failure, immediately FAILED") {
        val (publisher, processor, store) = buildPipeline()

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withStatus(400)),
        )

        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", """{"id":"1"}"""), deliveryInfo()) }
        db.jdbc.withTransaction { processor.processNext() }

        val counts = db.jdbc.withTransaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.FAILED to 1L)
        counts shouldContain (OutboxStatus.PENDING to 0L)
    }

    test("connection reset - retriable, stays PENDING") {
        val (publisher, processor, store) = buildPipeline()

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", """{"id":"1"}"""), deliveryInfo()) }
        db.jdbc.withTransaction { processor.processNext() }

        val counts = db.jdbc.withTransaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.PENDING to 1L)
    }

    test("transaction rollback - entry not persisted") {
        val (publisher, _, store) = buildPipeline()

        runCatching {
            db.jdbc.withTransaction {
                publisher.publish(OutboxMessage("order.created", """{"id":"1"}"""), deliveryInfo())
                error("Simulated business logic failure")
            }
        }

        val counts = db.jdbc.withTransaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.PENDING to 0L)
    }

    test("retry exhaustion - retries then FAILED") {
        val (publisher, processor, store) = buildPipeline(maxRetries = 3)

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withStatus(500)),
        )

        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", """{"id":"1"}"""), deliveryInfo()) }

        // First 3 processNext calls: retries 0->1, 1->2, 2->3 — stays PENDING
        repeat(3) {
            db.jdbc.withTransaction { processor.processNext() }
            val counts = db.jdbc.withTransaction { store.countByStatuses() }
            counts shouldContain (OutboxStatus.PENDING to 1L)
        }

        // 4th processNext: retries==3, shouldRetry(3) returns false -> FAILED
        db.jdbc.withTransaction { processor.processNext() }

        val counts = db.jdbc.withTransaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.FAILED to 1L)
        counts shouldContain (OutboxStatus.PENDING to 0L)
    }
})
