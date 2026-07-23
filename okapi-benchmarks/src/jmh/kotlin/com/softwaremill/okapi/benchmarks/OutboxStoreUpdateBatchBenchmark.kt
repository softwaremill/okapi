package com.softwaremill.okapi.benchmarks

import com.softwaremill.okapi.benchmarks.support.PostgresBenchmarkSupport
import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Isolates the UPDATE phase of outbox processing (KOJAK-75): compares [TOTAL_ENTRIES]
 * individual [PostgresOutboxStore.updateAfterProcessing] calls against a single
 * [PostgresOutboxStore.updateAfterProcessingBatch] `executeBatch()` call.
 *
 * Unlike [KafkaThroughputBenchmark] / [HttpThroughputBenchmark], delivery itself is not
 * measured here — entries are pre-computed as already-processed in [setupInvocation], so
 * both benchmarks time only the persistence roundtrip(s).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class OutboxStoreUpdateBatchBenchmark {

    private lateinit var postgres: PostgresBenchmarkSupport
    private lateinit var store: PostgresOutboxStore
    private lateinit var publisher: OutboxPublisher
    private lateinit var processedEntries: List<OutboxEntry>

    @Setup(Level.Trial)
    fun setupTrial() {
        postgres = PostgresBenchmarkSupport().also { it.start() }
        store = PostgresOutboxStore(postgres.jdbc)
        publisher = OutboxPublisher(store, Clock.systemUTC())
    }

    @Setup(Level.Invocation)
    fun setupInvocation() {
        postgres.truncate()
        postgres.jdbc.withTransaction {
            repeat(TOTAL_ENTRIES) {
                publisher.publish(OutboxMessage("bench.event", PAYLOAD), BenchDeliveryInfo)
            }
        }
        val claimed = postgres.jdbc.withTransaction { store.claimPending(TOTAL_ENTRIES) }
        val now = Clock.systemUTC().instant()
        processedEntries = claimed.map { it.toDelivered(now) }
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_ENTRIES)
    fun individualUpdates() {
        postgres.jdbc.withTransaction {
            processedEntries.forEach { store.updateAfterProcessing(it) }
        }
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_ENTRIES)
    fun batchUpdate() {
        postgres.jdbc.withTransaction {
            store.updateAfterProcessingBatch(processedEntries)
        }
    }

    @TearDown(Level.Trial)
    fun teardown() {
        postgres.stop()
    }

    private object BenchDeliveryInfo : DeliveryInfo {
        override val type = "bench"

        override fun serialize(): String = """{"type":"bench"}"""
    }

    companion object {
        const val TOTAL_ENTRIES = 1000
        private const val PAYLOAD = """{"orderId":"order-42","amount":100.50,"currency":"EUR"}"""
    }
}
