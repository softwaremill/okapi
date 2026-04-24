package com.softwaremill.okapi.micrometer

import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Micrometer gauges for outbox state — entry count and lag per status.
 *
 * Two `MultiGauge` instances are registered with the supplied [MeterRegistry]:
 * - `okapi.entries.count` (tag: `status`)
 * - `okapi.entries.lag.seconds` (tag: `status`)
 *
 * Values are updated by calling [refresh]. Each call performs a single store query
 * for counts and a single query for oldest-createdAt — both within one transaction
 * (when [transactionRunner] is supplied) so the snapshot is consistent. All status
 * rows are then registered atomically via [MultiGauge.register].
 *
 * `refresh()` is framework-agnostic. Wire it into your scheduler:
 * - **Spring Boot** with `okapi-spring-boot`: auto-wired (a refresher bean calls `refresh()` periodically).
 * - **Ktor / plain JVM:** use [OutboxMetricsRefresher], or call `refresh()` from your own scheduler.
 *
 * Store implementations backed by Exposed (e.g. `PostgresOutboxStore`, `MysqlOutboxStore`)
 * require an active transaction on the calling thread, so a [TransactionRunner] must be
 * supplied in that case. A read-only [TransactionRunner] is recommended.
 *
 * If a refresh fails, all rows of both gauges are set to [Double.NaN] and the exception
 * is logged at WARN. This surfaces database outages as visible metric gaps instead of
 * silently freezing on stale values.
 */
class MicrometerOutboxMetrics(
    private val store: OutboxStore,
    registry: MeterRegistry,
    private val transactionRunner: TransactionRunner? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val countGauge = MultiGauge.builder("okapi.entries.count").register(registry)
    private val lagGauge = MultiGauge.builder("okapi.entries.lag.seconds").register(registry)

    /**
     * Queries the store and atomically replaces both gauge snapshots with the result.
     *
     * On store failure, all rows are set to NaN and the exception is logged.
     * Safe to call concurrently — `MultiGauge.register` is atomic.
     */
    fun refresh() {
        val snapshot = querySnapshot()
        val now = clock.instant()
        publishCounts(snapshot)
        publishLags(snapshot, now)
    }

    private fun querySnapshot(): Snapshot? = try {
        val statuses = OutboxStatus.entries.toSet()
        val read = { Snapshot(store.countByStatuses(), store.findOldestCreatedAt(statuses)) }
        if (transactionRunner != null) transactionRunner.runInTransaction(read) else read()
    } catch (e: Exception) {
        logger.warn("Failed to read outbox metrics snapshot from store", e)
        null
    }

    private fun publishCounts(snapshot: Snapshot?) {
        countGauge.register(
            OutboxStatus.entries.map { status ->
                val value = if (snapshot == null) Double.NaN else (snapshot.counts[status] ?: 0L).toDouble()
                MultiGauge.Row.of(Tags.of("status", status.name.lowercase()), value)
            },
            true,
        )
    }

    private fun publishLags(snapshot: Snapshot?, now: Instant) {
        lagGauge.register(
            OutboxStatus.entries.map { status ->
                val value = when {
                    snapshot == null -> Double.NaN
                    else -> snapshot.oldest[status]
                        ?.let { Duration.between(it, now).toMillis() / 1000.0 } ?: 0.0
                }
                MultiGauge.Row.of(Tags.of("status", status.name.lowercase()), value)
            },
            true,
        )
    }

    private data class Snapshot(
        val counts: Map<OutboxStatus, Long>,
        val oldest: Map<OutboxStatus, Instant>,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(MicrometerOutboxMetrics::class.java)
    }
}
