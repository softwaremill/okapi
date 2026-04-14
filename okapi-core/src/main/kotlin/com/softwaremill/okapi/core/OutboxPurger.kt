package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically removes DELIVERED outbox entries older than [OutboxPurgerConfig.retention].
 *
 * Runs on a single daemon thread with explicit [start]/[stop] lifecycle.
 * [start] and [stop] are single-use -- the internal executor cannot be restarted after shutdown.
 * [AtomicBoolean] guards against accidental double-start, not restart.
 *
 * Delegates to [OutboxStore.removeDeliveredBefore] -- works with any storage adapter.
 */
class OutboxPurger @JvmOverloads constructor(
    private val outboxStore: OutboxStore,
    private val config: OutboxPurgerConfig = OutboxPurgerConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val running = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "outbox-purger").apply { isDaemon = true }
        }

    fun start() {
        check(!scheduler.isShutdown) { "OutboxPurger cannot be restarted after stop()" }
        if (!running.compareAndSet(false, true)) return
        logger.info(
            "Outbox purger started [retention={}, interval={}, batchSize={}]",
            config.retention,
            config.interval,
            config.batchSize,
        )
        val intervalMs = config.interval.toMillis()
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
            val cutoff = clock.instant().minus(config.retention)
            var totalDeleted = 0
            var batches = 0
            do {
                val deleted = outboxStore.removeDeliveredBefore(cutoff, config.batchSize)
                totalDeleted += deleted
                batches++
            } while (deleted == config.batchSize && batches < MAX_BATCHES_PER_TICK)

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
