package com.softwaremill.okapi.benchmarks

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.http.HttpDeliveryInfo
import com.softwaremill.okapi.http.HttpMessageDeliverer
import com.softwaremill.okapi.http.HttpMethod
import com.softwaremill.okapi.http.ServiceUrlResolver
import com.softwaremill.okapi.kafka.KafkaDeliveryInfo
import com.softwaremill.okapi.kafka.KafkaMessageDeliverer
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Pure code-overhead microbenchmarks for the [KafkaMessageDeliverer] and
 * [HttpMessageDeliverer] `deliver()` methods. I/O is mocked away:
 *
 * - Kafka: [MockProducer] with auto-complete (futures complete synchronously, no broker)
 * - HTTP: WireMock on loopback with zero artificial latency
 *
 * These measure the cost of: deliveryInfo deserialization, record/request construction,
 * exception classification, and result wrapping — i.e. everything around the I/O.
 * Useful as a baseline for "did optimization X add overhead?" before/after comparisons.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class DelivererMicroBenchmark {

    private lateinit var kafkaDeliverer: KafkaMessageDeliverer
    private lateinit var httpDeliverer: HttpMessageDeliverer
    private lateinit var wiremock: WireMockServer

    private lateinit var kafkaEntry: OutboxEntry
    private lateinit var httpEntry: OutboxEntry

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    fun setupTrial() {
        val mockProducer = MockProducer(true, null, StringSerializer(), StringSerializer())
        kafkaDeliverer = KafkaMessageDeliverer(mockProducer)

        wiremock = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }
        wiremock.stubFor(post(urlEqualTo(ENDPOINT)).willReturn(aResponse().withStatus(200)))
        val urlResolver = ServiceUrlResolver { "http://localhost:${wiremock.port()}" }
        httpDeliverer = HttpMessageDeliverer(urlResolver)

        val now = Instant.now()
        kafkaEntry = OutboxEntry.createPending(
            OutboxMessage("bench.event", PAYLOAD),
            KafkaDeliveryInfo(topic = "bench-topic", partitionKey = "k"),
            now,
        )
        httpEntry = OutboxEntry.createPending(
            OutboxMessage("bench.event", PAYLOAD),
            HttpDeliveryInfo(serviceName = "bench", endpointPath = ENDPOINT, httpMethod = HttpMethod.POST),
            now,
        )
    }

    @Benchmark
    fun kafkaDeliver(): DeliveryResult = kafkaDeliverer.deliver(kafkaEntry)

    @Benchmark
    fun httpDeliver(): DeliveryResult = httpDeliverer.deliver(httpEntry)

    @TearDown(org.openjdk.jmh.annotations.Level.Trial)
    fun teardown() {
        wiremock.stop()
    }

    companion object {
        private const val ENDPOINT = "/api/bench"
        private const val PAYLOAD = """{"orderId":"order-42","amount":100.50,"currency":"EUR"}"""
    }
}
