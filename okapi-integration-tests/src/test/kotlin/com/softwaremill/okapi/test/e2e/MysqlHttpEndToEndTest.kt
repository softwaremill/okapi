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
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

class MysqlHttpEndToEndTest : FunSpec({
    val db = MysqlTestSupport()
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

    fun buildPipeline(): Triple<OutboxPublisher, OutboxProcessor, MysqlOutboxStore> {
        val clock = Clock.systemUTC()
        val store = MysqlOutboxStore(clock)
        val publisher = OutboxPublisher(store, clock)
        val urlResolver = ServiceUrlResolver { "http://localhost:${wiremock.port()}" }
        val entryProcessor = OutboxEntryProcessor(
            HttpMessageDeliverer(urlResolver),
            RetryPolicy(maxRetries = 3),
            clock,
        )
        return Triple(publisher, OutboxProcessor(store, entryProcessor), store)
    }

    fun deliveryInfo() = httpDeliveryInfo {
        serviceName = "notification-service"
        endpointPath = "/api/notify"
    }

    test("HTTP 200 - message delivered") {
        val (publisher, processor, store) = buildPipeline()
        val payload = """{"orderId":"abc-123"}"""

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withStatus(200)),
        )

        transaction { publisher.publish(OutboxMessage("order.created", payload), deliveryInfo()) }
        transaction { processor.processNext() }

        wiremock.verify(
            postRequestedFor(urlEqualTo("/api/notify"))
                .withRequestBody(equalTo(payload)),
        )

        val counts = transaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.DELIVERED to 1L)
    }

    test("HTTP 500 - retriable, PENDING") {
        val (publisher, processor, store) = buildPipeline()

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withStatus(500)),
        )

        transaction { publisher.publish(OutboxMessage("order.created", """{"id":"1"}"""), deliveryInfo()) }
        transaction { processor.processNext() }

        val counts = transaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.PENDING to 1L)
        counts shouldContain (OutboxStatus.DELIVERED to 0L)
    }

    test("HTTP 400 - permanent, FAILED") {
        val (publisher, processor, store) = buildPipeline()

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withStatus(400)),
        )

        transaction { publisher.publish(OutboxMessage("order.created", """{"id":"1"}"""), deliveryInfo()) }
        transaction { processor.processNext() }

        val counts = transaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.FAILED to 1L)
        counts shouldContain (OutboxStatus.PENDING to 0L)
    }

    test("connection reset - retriable, PENDING") {
        val (publisher, processor, store) = buildPipeline()

        wiremock.stubFor(
            post(urlEqualTo("/api/notify"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        transaction { publisher.publish(OutboxMessage("order.created", """{"id":"1"}"""), deliveryInfo()) }
        transaction { processor.processNext() }

        val counts = transaction { store.countByStatuses() }
        counts shouldContain (OutboxStatus.PENDING to 1L)
    }
})
