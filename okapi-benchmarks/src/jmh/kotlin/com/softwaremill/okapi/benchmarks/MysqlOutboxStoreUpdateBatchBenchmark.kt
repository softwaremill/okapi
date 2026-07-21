package com.softwaremill.okapi.benchmarks

import com.softwaremill.okapi.benchmarks.support.MysqlBenchmarkSupport
import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.mysql.MysqlOutboxStore
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
import java.util.concurrent.TimeUnit

/**
 * MySQL counterpart of [OutboxStoreUpdateBatchBenchmark] (KOJAK-75), parameterized on
 * `rewriteBatchedStatements` — Connector/J's `executeBatch()` sends one roundtrip per statement
 * unless this JDBC URL property is set, unlike PgJDBC which pipelines batches natively. This
 * benchmark quantifies the gap; see README "Performance" section.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class MysqlOutboxStoreUpdateBatchBenchmark {

    @Param("false", "true")
    lateinit var rewriteBatchedStatements: String

    private lateinit var mysql: MysqlBenchmarkSupport
    private lateinit var store: MysqlOutboxStore
    private lateinit var publisher: OutboxPublisher
    private lateinit var processedEntries: List<OutboxEntry>

    @Setup(Level.Trial)
    fun setupTrial() {
        mysql = MysqlBenchmarkSupport(rewriteBatchedStatements.toBoolean()).also { it.start() }
        store = MysqlOutboxStore(mysql.jdbc)
        publisher = OutboxPublisher(store, Clock.systemUTC())
    }

    @Setup(Level.Invocation)
    fun setupInvocation() {
        mysql.truncate()
        mysql.jdbc.withTransaction {
            repeat(TOTAL_ENTRIES) {
                publisher.publish(OutboxMessage("bench.event", PAYLOAD), BenchDeliveryInfo)
            }
        }
        val claimed = mysql.jdbc.withTransaction { store.claimPending(TOTAL_ENTRIES) }
        val now = Clock.systemUTC().instant()
        processedEntries = claimed.map { it.toDelivered(now) }
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_ENTRIES)
    fun individualUpdates() {
        mysql.jdbc.withTransaction {
            processedEntries.forEach { store.updateAfterProcessing(it) }
        }
    }

    @Benchmark
    @OperationsPerInvocation(TOTAL_ENTRIES)
    fun batchUpdate() {
        mysql.jdbc.withTransaction {
            store.updateAfterProcessingBatch(processedEntries)
        }
    }

    @TearDown(Level.Trial)
    fun teardown() {
        mysql.stop()
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
