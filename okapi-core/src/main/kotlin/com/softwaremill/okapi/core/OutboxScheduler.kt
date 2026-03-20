package com.softwaremill.okapi.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Standalone scheduler that periodically calls [OutboxProcessor.processNext].
 *
 * Each tick is optionally wrapped in a transaction via [transactionRunner].
 * Runs on a single daemon thread and provides explicit [start]/[stop] lifecycle.
 *
 * Framework-specific modules hook into their own lifecycle events:
 * - `okapi-spring`: `SmartInitializingSingleton` / `DisposableBean`
 * - `okapi-ktor`: `ApplicationStarted` / `ApplicationStopped`
 */
class OutboxScheduler(
    private val outboxProcessor: OutboxProcessor,
    private val transactionRunner: TransactionRunner? = null,
    private val intervalMs: Long = 1_000L,
    private val batchSize: Int = 10,
) {
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "outbox-processor").apply { isDaemon = true }
        }

    fun start() {
        scheduler.scheduleWithFixedDelay(
            ::tick,
            0L,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
    }

    private fun tick() {
        if (transactionRunner != null) {
            transactionRunner.runInTransaction { outboxProcessor.processNext(batchSize) }
        } else {
            outboxProcessor.processNext(batchSize)
        }
    }
}
