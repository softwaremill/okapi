package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone scheduler that periodically calls [OutboxProcessor.processNext].
 *
 * Each tick is optionally wrapped in a transaction via [transactionRunner].
 * Runs on a single daemon thread with explicit [start]/[stop] lifecycle.
 * [start] and [stop] are single-use -- the internal executor cannot be restarted after shutdown.
 * [AtomicBoolean] guards against accidental double-start, not restart.
 *
 * Framework-specific modules hook into their own lifecycle events:
 * - `okapi-spring-boot`: `SmartLifecycle`
 * - `okapi-ktor`: `ApplicationStarted` / `ApplicationStopped`
 */
class OutboxScheduler(
    private val outboxProcessor: OutboxProcessor,
    private val transactionRunner: TransactionRunner? = null,
    private val config: OutboxSchedulerConfig = OutboxSchedulerConfig(),
) {
    private val running = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "outbox-processor").apply { isDaemon = true }
        }

    fun start() {
        check(!scheduler.isShutdown) { "OutboxScheduler cannot be restarted after stop()" }
        if (!running.compareAndSet(false, true)) return
        logger.info("Outbox processor started [interval={}ms, batchSize={}]", config.intervalMs, config.batchSize)
        scheduler.scheduleWithFixedDelay(::tick, 0L, config.intervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
        logger.info("Outbox processor stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun tick() {
        try {
            transactionRunner?.runInTransaction { outboxProcessor.processNext(config.batchSize) }
                ?: outboxProcessor.processNext(config.batchSize)
            logger.debug("Outbox processor tick completed [batchSize={}]", config.batchSize)
        } catch (e: Exception) {
            logger.error("Outbox processor tick failed, will retry at next scheduled interval", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxScheduler::class.java)
    }
}
