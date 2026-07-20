package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone scheduler that periodically calls [OutboxProcessor.processNext].
 *
 * Each worker's call runs inside [transactionRunner]. The runner is required: without a
 * surrounding transaction, `FOR UPDATE SKIP LOCKED` releases its row lock at the end of the
 * claim SELECT and concurrent processor instances deliver the same entry multiple times.
 *
 * With [OutboxSchedulerConfig.concurrency] `> 1`, each tick fans out to that many workers on
 * [OutboxSchedulerConfig.workerExecutorFactory]'s pool, each independently claiming and
 * processing its own batch -- `FOR UPDATE SKIP LOCKED` guarantees disjoint claims, so no
 * app-level coordination is needed. The tick waits for every worker before the next scheduled
 * interval, so ticks never overlap. `concurrency = 1` (default) skips the executor entirely
 * and calls the batch inline, preserving the original zero-overhead single-worker behavior.
 *
 * Runs on a single daemon thread with explicit [start]/[stop] lifecycle.
 * [start] and [stop] are single-use -- the internal executor cannot be restarted after shutdown.
 * [AtomicBoolean] guards against accidental double-start, not restart.
 *
 * Framework-specific modules hook into their own lifecycle events:
 * - `okapi-spring-boot`: `SmartLifecycle`
 * - `okapi-ktor`: `ApplicationStarted` / `ApplicationStopped`
 */
class OutboxScheduler @JvmOverloads constructor(
    private val outboxProcessor: OutboxProcessor,
    private val transactionRunner: TransactionRunner,
    private val config: OutboxSchedulerConfig = OutboxSchedulerConfig(),
) {
    private val running = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "outbox-processor").apply { isDaemon = true }
        }

    // spin up threads only if needed by lazy
    private val workers: Lazy<ExecutorService?> =
        lazy(LazyThreadSafetyMode.NONE) {
            if (config.concurrency > 1) config.workerExecutorFactory(config.concurrency) else null
        }

    fun start() {
        check(!scheduler.isShutdown) { "OutboxScheduler cannot be restarted after stop()" }
        if (!running.compareAndSet(false, true)) return
        logger.info(
            "Outbox processor started [interval={}, batchSize={}, concurrency={}]",
            config.interval,
            config.batchSize,
            config.concurrency,
        )
        scheduler.scheduleWithFixedDelay(::tick, 0L, config.interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
        if (workers.isInitialized()) {
            workers.value?.let {
                it.shutdown()
                if (!it.awaitTermination(5, TimeUnit.SECONDS)) it.shutdownNow()
            }
        }
        logger.info("Outbox processor stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun tick() {
        val pool = workers.value
        if (pool == null) {
            processBatch()
        } else {
            val futures = (1..config.concurrency).map { pool.submit(::processBatch) }
            futures.forEach(::awaitWorker)
        }
    }

    /**
     * Awaits one worker's completion without letting anything escape [tick] -- an uncaught
     * exception here would make `scheduleWithFixedDelay` suppress all future ticks, silently
     * killing the scheduler. [processBatch] already catches [Exception] internally, so this
     * only guards against an [Error] surfacing via [ExecutionException], or an interrupt while
     * awaiting.
     */
    private fun awaitWorker(future: Future<*>) {
        try {
            future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while awaiting outbox worker; will retry at next scheduled interval", e)
        } catch (e: ExecutionException) {
            logger.error("Outbox worker failed unexpectedly, will retry at next scheduled interval", e.cause ?: e)
        }
    }

    private fun processBatch() {
        try {
            transactionRunner.runInTransaction { outboxProcessor.processNext(config.batchSize) }
            logger.debug("Outbox processor tick completed [batchSize={}]", config.batchSize)
        } catch (e: Exception) {
            logger.error("Outbox processor tick failed, will retry at next scheduled interval", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxScheduler::class.java)
    }
}
