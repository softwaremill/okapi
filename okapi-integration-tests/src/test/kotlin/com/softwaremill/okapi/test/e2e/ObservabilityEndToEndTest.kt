package com.softwaremill.okapi.test.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.http.HttpMessageDeliverer
import com.softwaremill.okapi.http.ServiceUrlResolver
import com.softwaremill.okapi.http.httpDeliveryInfo
import com.softwaremill.okapi.micrometer.MicrometerOutboxListener
import com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.util.concurrent.TimeUnit

class ObservabilityEndToEndTest : FunSpec({
    val db = PostgresTestSupport()
    val wiremock = WireMockServer(wireMockConfig().dynamicPort())
    val clock = Clock.systemUTC()

    val jdbcTransactionRunner = object : TransactionRunner {
        override fun <T> runInTransaction(block: () -> T): T = db.jdbc.withTransaction { block() }
    }

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

    fun deliveryInfo() = httpDeliveryInfo {
        serviceName = "test-service"
        endpointPath = "/api/webhook"
    }

    test("full pipeline: publish, deliver, verify Micrometer counters and gauges") {
        val registry = SimpleMeterRegistry()
        val store = PostgresOutboxStore(db.jdbc, clock)
        val publisher = OutboxPublisher(store, clock)
        val listener = MicrometerOutboxListener(registry)
        val metrics = MicrometerOutboxMetrics(store, registry, transactionRunner = jdbcTransactionRunner, clock = clock)

        val urlResolver = ServiceUrlResolver { "http://localhost:${wiremock.port()}" }
        val entryProcessor = OutboxEntryProcessor(HttpMessageDeliverer(urlResolver), RetryPolicy(maxRetries = 3), clock)
        val processor = OutboxProcessor(store, entryProcessor, listener = listener, clock = clock)

        // Stub: first call → 500 (retriable), second call → 200 (success)
        wiremock.stubFor(
            post(urlEqualTo("/api/webhook"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("first-failed"),
        )
        wiremock.stubFor(
            post(urlEqualTo("/api/webhook"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs("first-failed")
                .willReturn(aResponse().withStatus(200)),
        )

        // Publish 1 message
        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", """{"orderId":"e2e-1"}"""), deliveryInfo()) }

        // First processNext: HTTP 500 → RetryScheduled
        db.jdbc.withTransaction { processor.processNext() }

        registry.counter("okapi.entries.retry.scheduled").count() shouldBe 1.0
        registry.counter("okapi.entries.delivered").count() shouldBe 0.0
        registry.counter("okapi.entries.failed").count() shouldBe 0.0
        registry.timer("okapi.batch.duration").count() shouldBe 1

        // Gauge snapshot is push-based — refresh before reading
        metrics.refresh()
        registry.find("okapi.entries.count").tag("status", "pending").gauge()!!.value() shouldBe 1.0

        // Second processNext: HTTP 200 → Delivered
        db.jdbc.withTransaction { processor.processNext() }

        registry.counter("okapi.entries.delivered").count() shouldBe 1.0
        registry.counter("okapi.entries.retry.scheduled").count() shouldBe 1.0 // still 1 from before
        registry.timer("okapi.batch.duration").count() shouldBe 2

        // Refresh again and verify new gauge snapshot
        metrics.refresh()
        registry.find("okapi.entries.count").tag("status", "pending").gauge()!!.value() shouldBe 0.0
        registry.find("okapi.entries.count").tag("status", "delivered").gauge()!!.value() shouldBe 1.0
    }

    test("permanent failure: HTTP 400 → Failed counter incremented, gauge reflects FAILED") {
        val registry = SimpleMeterRegistry()
        val store = PostgresOutboxStore(db.jdbc, clock)
        val publisher = OutboxPublisher(store, clock)
        val listener = MicrometerOutboxListener(registry)
        val metrics = MicrometerOutboxMetrics(store, registry, transactionRunner = jdbcTransactionRunner, clock = clock)

        val urlResolver = ServiceUrlResolver { "http://localhost:${wiremock.port()}" }
        val entryProcessor = OutboxEntryProcessor(HttpMessageDeliverer(urlResolver), RetryPolicy(maxRetries = 3), clock)
        val processor = OutboxProcessor(store, entryProcessor, listener = listener, clock = clock)

        wiremock.stubFor(
            post(urlEqualTo("/api/webhook"))
                .willReturn(aResponse().withStatus(400)),
        )

        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", """{"orderId":"e2e-2"}"""), deliveryInfo()) }
        db.jdbc.withTransaction { processor.processNext() }

        registry.counter("okapi.entries.failed").count() shouldBe 1.0
        registry.counter("okapi.entries.delivered").count() shouldBe 0.0

        metrics.refresh()
        registry.find("okapi.entries.count").tag("status", "failed").gauge()!!.value() shouldBe 1.0
    }

    test("batch duration timer records realistic delivery time") {
        val registry = SimpleMeterRegistry()
        val store = PostgresOutboxStore(db.jdbc, clock)
        val publisher = OutboxPublisher(store, clock)
        val listener = MicrometerOutboxListener(registry)

        val urlResolver = ServiceUrlResolver { "http://localhost:${wiremock.port()}" }
        val entryProcessor = OutboxEntryProcessor(HttpMessageDeliverer(urlResolver), RetryPolicy(maxRetries = 3), clock)
        val processor = OutboxProcessor(store, entryProcessor, listener = listener, clock = clock)

        wiremock.stubFor(
            post(urlEqualTo("/api/webhook"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(50)),
        )

        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", """{"orderId":"e2e-3"}"""), deliveryInfo()) }
        db.jdbc.withTransaction { processor.processNext() }

        val timer = registry.timer("okapi.batch.duration")
        timer.count() shouldBe 1
        timer.totalTime(TimeUnit.MILLISECONDS) shouldBeGreaterThanOrEqual 50.0
    }

    test("lag gauge reflects real time difference for pending entries") {
        val registry = SimpleMeterRegistry()
        val store = PostgresOutboxStore(db.jdbc, clock)
        val publisher = OutboxPublisher(store, clock)
        val metrics = MicrometerOutboxMetrics(store, registry, transactionRunner = jdbcTransactionRunner, clock = clock)

        // Publish but don't process — entry stays PENDING
        db.jdbc.withTransaction { publisher.publish(OutboxMessage("order.created", """{"orderId":"e2e-4"}"""), deliveryInfo()) }

        // Small sleep to create measurable lag
        Thread.sleep(100)

        metrics.refresh()
        val lagGauge = registry.find("okapi.entries.lag.seconds").tag("status", "pending").gauge()
        lagGauge!!.value() shouldBeGreaterThanOrEqual 0.05 // at least 50ms lag
    }
})
