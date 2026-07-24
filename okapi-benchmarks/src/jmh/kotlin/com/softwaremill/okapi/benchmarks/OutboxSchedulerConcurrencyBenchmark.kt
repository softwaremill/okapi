package com.softwaremill.okapi.benchmarks

import com.softwaremill.okapi.benchmarks.support.KafkaBenchmarkSupport
import com.softwaremill.okapi.benchmarks.support.PostgresBenchmarkSupport
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxSchedulerConfig
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.kafka.KafkaDeliveryInfo
import com.softwaremill.okapi.kafka.KafkaMessageDeliverer
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import org.apache.kafka.clients.producer.KafkaProducer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Measures scheduler fan-out throughput (KOJAK-77): each round dispatches [concurrency]
 * parallel [OutboxProcessor.processNext] calls -- one per worker, each on its own JDBC
 * transaction/connection, exactly mirroring what [com.softwaremill.okapi.core.OutboxScheduler]'s
 * internal fan-out does -- and finds the platform-vs-virtual-thread breakeven point.
 *
 * The scheduler's [java.util.concurrent.ScheduledExecutorService] polling loop is bypassed
 * (same rationale as [KafkaThroughputBenchmark]): we measure worker-pool throughput, not
 * polling cadence. Kafka `deliverBatch` is the transport, since it's the one delivery path
 * fast enough that the fan-out itself -- not delivery I/O -- is the bottleneck being measured
 * (HTTP `deliverBatch` isn't implemented yet; KOJAK-74).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class OutboxSchedulerConcurrencyBenchmark {

    @Param("platform", "virtual")
    var executorType: String = "platform"

    @Param("1", "4", "16", "64")
    var concurrency: Int = 1

    private lateinit var postgres: PostgresBenchmarkSupport
    private lateinit var kafka: KafkaBenchmarkSupport
    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var publisher: OutboxPublisher
    private lateinit var processor: OutboxProcessor
    private lateinit var executor: ExecutorService
    private lateinit var topic: String

    @Setup(Level.Trial)
    fun setupTrial() {
        postgres = PostgresBenchmarkSupport().also { it.start() }
        kafka = KafkaBenchmarkSupport().also { it.start() }
        producer = kafka.createProducer()
        topic = "bench-${UUID.randomUUID()}"

        val clock = Clock.systemUTC()
        val store = PostgresOutboxStore(postgres.jdbc)
        publisher = OutboxPublisher(store, clock)
        val deliverer = KafkaMessageDeliverer(producer)
        val entryProcessor = OutboxEntryProcessor(deliverer, RetryPolicy(maxRetries = 0), clock)
        processor = OutboxProcessor(store, entryProcessor)

        executor = when (executorType) {
            "virtual" -> OutboxSchedulerConfig.virtualThreadPool(concurrency)
            else -> OutboxSchedulerConfig.defaultPlatformPool(concurrency)
        }
    }

    @Setup(Level.Invocation)
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
        var remaining = TOTAL_ENTRIES
        while (remaining > 0) {
            val futures = (1..concurrency).map {
                executor.submit(Callable { postgres.jdbc.withTransaction { processor.processNext(BATCH_SIZE) } })
            }
            val processedThisRound = futures.sumOf { it.get() }
            // A stalled round must fail loudly, not `break` silently -- @OperationsPerInvocation(TOTAL_ENTRIES)
            // would otherwise have JMH divide elapsed time by a count of entries that were never actually
            // processed, silently under-reporting the per-op cost.
            check(processedThisRound > 0) {
                "drainAll() stalled with $remaining/$TOTAL_ENTRIES entries left unprocessed " +
                    "(concurrency=$concurrency, batchSize=$BATCH_SIZE, executorType=$executorType)"
            }
            remaining -= processedThisRound
        }
        check(remaining == 0) { "drainAll() finished with $remaining/$TOTAL_ENTRIES entries still unprocessed" }
    }

    @TearDown(Level.Trial)
    fun teardown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(EXECUTOR_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
        producer.close()
        kafka.stop()
        postgres.stop()
    }

    companion object {
        const val TOTAL_ENTRIES = 6400
        const val BATCH_SIZE = 100
        private const val PAYLOAD = """{"orderId":"order-42","amount":100.50,"currency":"EUR"}"""
        private const val EXECUTOR_TERMINATION_TIMEOUT_SECONDS = 5L
    }
}
