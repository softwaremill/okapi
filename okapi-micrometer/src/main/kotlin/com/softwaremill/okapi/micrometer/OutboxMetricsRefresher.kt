package com.softwaremill.okapi.micrometer

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Periodically calls [MicrometerOutboxMetrics.refresh] from a single-thread daemon executor.
 *
 * Use when your application does not have a framework-managed scheduler (Ktor, plain JVM).
 * Spring Boot users get an equivalent bean via `okapi-spring-boot` auto-configuration.
 *
 * Lifecycle:
 * ```
 * val refresher = OutboxMetricsRefresher(metrics, Duration.ofSeconds(15))
 * refresher.start()
 * // ... application runs ...
 * refresher.close()
 * ```
 *
 * The first refresh runs immediately on [start] so gauges are not empty on the first scrape.
 *
 * Exceptions thrown by [MicrometerOutboxMetrics.refresh] are caught and logged to keep
 * the executor alive. Single-thread design prevents overlapping refreshes if a tick takes
 * longer than [interval] — the next tick runs after the previous one completes.
 */
class OutboxMetricsRefresher(
    private val metrics: MicrometerOutboxMetrics,
    private val interval: Duration,
    private val threadName: String = "okapi-metrics-refresher",
) : AutoCloseable {

    init {
        require(!interval.isNegative && interval.toMillis() > 0) { "interval must be at least 1ms" }
    }

    private var executor: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (executor != null) return
        val es = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, threadName).apply { isDaemon = true }
        }
        executor = es
        task = es.scheduleWithFixedDelay(::runRefresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun close() {
        task?.cancel(false)
        task = null
        val es = executor ?: return
        executor = null
        es.shutdown()
        if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
            es.shutdownNow()
        }
    }

    private fun runRefresh() {
        try {
            metrics.refresh()
        } catch (e: Exception) {
            logger.warn("Outbox metrics refresh failed (will retry next tick)", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxMetricsRefresher::class.java)
    }
}
