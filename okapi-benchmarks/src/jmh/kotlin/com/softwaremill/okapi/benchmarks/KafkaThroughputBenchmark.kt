package com.softwaremill.okapi.benchmarks

import com.softwaremill.okapi.benchmarks.support.KafkaBenchmarkSupport
import com.softwaremill.okapi.benchmarks.support.PostgresBenchmarkSupport
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.kafka.KafkaDeliveryInfo
import com.softwaremill.okapi.kafka.KafkaMessageDeliverer
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import org.apache.kafka.clients.producer.KafkaProducer
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Measures end-to-end fanout throughput for Kafka delivery.
 *
 * Each invocation:
 *   1. (Setup.Invocation) truncates the outbox table and inserts [TOTAL_ENTRIES] PENDING entries.
 *   2. (Benchmark) calls [OutboxProcessor.processNext] in a loop until the queue is drained.
 *   3. JMH reports avg ms per "drain TOTAL_ENTRIES entries", from which msg/s is derived.
 *
 * The scheduler is bypassed deliberately — we measure the processing capacity, not polling cadence.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class KafkaThroughputBenchmark {

    @Param("10", "50", "100")
    var batchSize: Int = 0

    private lateinit var postgres: PostgresBenchmarkSupport
    private lateinit var kafka: KafkaBenchmarkSupport
    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var publisher: OutboxPublisher
    private lateinit var processor: OutboxProcessor
    private lateinit var topic: String

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    fun setupTrial() {
        postgres = PostgresBenchmarkSupport().also { it.start() }
        kafka = KafkaBenchmarkSupport().also { it.start() }
        producer = kafka.createProducer()
        topic = "bench-${UUID.randomUUID()}"

        val clock = Clock.systemUTC()
        val store = PostgresOutboxStore(postgres.jdbc, clock)
        publisher = OutboxPublisher(store, clock)
        val deliverer = KafkaMessageDeliverer(producer)
        val entryProcessor = OutboxEntryProcessor(deliverer, RetryPolicy(maxRetries = 0), clock)
        processor = OutboxProcessor(store, entryProcessor)
    }

    @Setup(org.openjdk.jmh.annotations.Level.Invocation)
    fun setupInvocation() {
        postgres.truncate()
        val info = KafkaDeliveryInfo(topic = topic, partitionKey = "k")
        postgres.jdbc.withTransaction {
            repeat(TOTAL_ENTRIES) {
                publisher.publish(OutboxMessage("bench.event", PAYLOAD), info)
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
        producer.close()
        kafka.stop()
        postgres.stop()
    }

    companion object {
        const val TOTAL_ENTRIES = 1000
        private const val PAYLOAD = """{"orderId":"order-42","amount":100.50,"currency":"EUR"}"""
    }
}
