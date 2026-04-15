package com.softwaremill.okapi.micrometer

import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Registers Micrometer gauges that poll [OutboxStore] on every Prometheus scrape.
 *
 * Registered metrics:
 * - `okapi.entries.count` (tag: status) — number of entries per status
 * - `okapi.entries.lag.seconds` (tag: status) — age of the oldest entry per status
 *
 * Gauge suppliers run on the Prometheus scrape thread, which has no ambient transaction.
 * Store implementations backed by Exposed (e.g. `PostgresOutboxStore`, `MysqlOutboxStore`)
 * require an active transaction on the calling thread, so a [TransactionRunner] must be supplied
 * when using such stores. A read-only [TransactionRunner] is recommended.
 *
 * If a store call throws, the gauge returns [Double.NaN] and the exception is logged at WARN.
 * This surfaces database outages as visible metric gaps instead of silently reporting zero.
 */
class MicrometerOutboxMetrics(
    private val store: OutboxStore,
    registry: MeterRegistry,
    private val transactionRunner: TransactionRunner? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        for (status in OutboxStatus.entries) {
            Gauge.builder("okapi.entries.count") { safeQuery { store.countByStatuses()[status]?.toDouble() ?: 0.0 } }
                .tag("status", status.name.lowercase())
                .register(registry)

            Gauge.builder("okapi.entries.lag.seconds") {
                safeQuery {
                    store.findOldestCreatedAt(setOf(status))[status]
                        ?.let { Duration.between(it, clock.instant()).toMillis() / 1000.0 } ?: 0.0
                }
            }.tag("status", status.name.lowercase()).register(registry)
        }
    }

    private fun safeQuery(query: () -> Double): Double = try {
        transactionRunner?.runInTransaction(query) ?: query()
    } catch (e: Exception) {
        logger.warn("Failed to read outbox metric from store", e)
        Double.NaN
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MicrometerOutboxMetrics::class.java)
    }
}
