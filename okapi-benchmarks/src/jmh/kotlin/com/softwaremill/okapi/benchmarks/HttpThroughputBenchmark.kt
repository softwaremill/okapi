package com.softwaremill.okapi.benchmarks

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.softwaremill.okapi.benchmarks.support.PostgresBenchmarkSupport
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.http.HttpDeliveryInfo
import com.softwaremill.okapi.http.HttpMessageDeliverer
import com.softwaremill.okapi.http.HttpMethod
import com.softwaremill.okapi.http.ServiceUrlResolver
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Measures end-to-end fanout throughput for HTTP webhook delivery via WireMock.
 *
 * WireMock is configured with **zero artificial latency** — this measures the
 * library's processing capacity, not the user's network. Real-world throughput
 * will be lower, dominated by webhook RTT.
 *
 * Same pattern as [KafkaThroughputBenchmark]: bypass scheduler, drain in tight loop,
 * report avg ms per drain with [OperationsPerInvocation] yielding ops/s = msg/s.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class HttpThroughputBenchmark {

    @Param("10", "50", "100")
    var batchSize: Int = 0

    /**
     * Artificial server-side latency injected by WireMock per request.
     *
     * - `0` — library-only ceiling (no I/O cost). Useful as upper bound.
     * - `20` — fast intra-cluster service (e.g., service mesh sidecar).
     * - `100` — typical external webhook (cross-region cloud HTTP).
     *
     * Real production webhook latency is usually 50-500 ms; pick the value closest
     * to your target service.
     */
    @Param("0", "20", "100")
    var httpLatencyMs: Int = 0

    private lateinit var postgres: PostgresBenchmarkSupport
    private lateinit var wiremock: WireMockServer
    private lateinit var publisher: OutboxPublisher
    private lateinit var processor: OutboxProcessor
    private lateinit var deliveryInfo: HttpDeliveryInfo

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    fun setupTrial() {
        postgres = PostgresBenchmarkSupport().also { it.start() }
        wiremock = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }
        wiremock.stubFor(
            post(urlEqualTo(ENDPOINT)).willReturn(
                aResponse()
                    .withStatus(200)
                    .withFixedDelay(httpLatencyMs),
            ),
        )

        val clock = Clock.systemUTC()
        val store = PostgresOutboxStore(postgres.jdbc, clock)
        publisher = OutboxPublisher(store, clock)
        val urlResolver = ServiceUrlResolver { "http://localhost:${wiremock.port()}" }
        val deliverer = HttpMessageDeliverer(urlResolver)
        val entryProcessor = OutboxEntryProcessor(deliverer, RetryPolicy(maxRetries = 0), clock)
        processor = OutboxProcessor(store, entryProcessor)
        deliveryInfo = HttpDeliveryInfo(
            serviceName = "bench-target",
            endpointPath = ENDPOINT,
            httpMethod = HttpMethod.POST,
        )
    }

    @Setup(org.openjdk.jmh.annotations.Level.Invocation)
    fun setupInvocation() {
        postgres.truncate()
        postgres.jdbc.withTransaction {
            repeat(TOTAL_ENTRIES) {
                publisher.publish(OutboxMessage("bench.event", PAYLOAD), deliveryInfo)
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_ENTRIES)
    fun drainAll() {
        val iterations = (TOTAL_ENTRIES + batchSize - 1) / batchSize
        repeat(iterations) {
            postgres.jdbc.withTransaction { processor.processNext(batchSize) }
        }
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Trial)
    fun teardown() {
        wiremock.stop()
        postgres.stop()
    }

    companion object {
        const val TOTAL_ENTRIES = 1000
        private const val ENDPOINT = "/api/bench"
        private const val PAYLOAD = """{"orderId":"order-42","amount":100.50,"currency":"EUR"}"""
    }
}
