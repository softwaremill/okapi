package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically removes DELIVERED outbox entries older than [retentionDuration].
 *
 * Runs on a single daemon thread with explicit [start]/[stop] lifecycle.
 * [start] and [stop] are single-use -- the internal executor cannot be restarted after shutdown.
 * [AtomicBoolean] guards against accidental double-start, not restart.
 *
 * Delegates to [OutboxStore.removeDeliveredBefore] -- works with any storage adapter.
 */
class OutboxPurger(
    private val outboxStore: OutboxStore,
    private val retentionDuration: Duration = Duration.ofDays(7),
    private val intervalMs: Long = 3_600_000L,
    private val batchSize: Int = 100,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(retentionDuration > Duration.ZERO) { "retentionDuration must be positive, got: $retentionDuration" }
        require(intervalMs > 0) { "intervalMs must be positive, got: $intervalMs" }
        require(batchSize > 0) { "batchSize must be positive, got: $batchSize" }
    }

    private val running = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "outbox-purger").apply { isDaemon = true }
        }

    fun start() {
        check(!scheduler.isShutdown) { "OutboxPurger cannot be restarted after stop()" }
        if (!running.compareAndSet(false, true)) return
        logger.info(
            "Outbox purger started [retention={}, interval={}ms, batchSize={}]",
            retentionDuration,
            intervalMs,
            batchSize,
        )
        scheduler.scheduleWithFixedDelay(::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
        logger.info("Outbox purger stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun tick() {
        try {
            val cutoff = clock.instant().minus(retentionDuration)
            var totalDeleted = 0
            var batches = 0
            do {
                val deleted = outboxStore.removeDeliveredBefore(cutoff, batchSize)
                totalDeleted += deleted
                batches++
            } while (deleted == batchSize && batches < MAX_BATCHES_PER_TICK)

            if (totalDeleted > 0) {
                logger.debug("Purged {} delivered entries in {} batches", totalDeleted, batches)
            }
        } catch (e: Exception) {
            logger.error("Outbox purge failed, will retry at next scheduled interval", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxPurger::class.java)
        private const val MAX_BATCHES_PER_TICK = 10
    }
}
