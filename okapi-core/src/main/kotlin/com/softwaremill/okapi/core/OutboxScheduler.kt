package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
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
 * [OutboxSchedulerConfig.workerExecutorFactory]'s pool. `FOR UPDATE SKIP LOCKED` guarantees
 * disjoint claims, but [outboxProcessor] (and its dependencies) must be safe for concurrent use.
 * The tick waits for every worker before the next scheduled interval, so ticks never overlap.
 * `concurrency = 1` (default) calls the batch inline, preserving single-worker semantics.
 *
 * At `concurrency > 1`, [outboxProcessor] -- and by extension its [OutboxStore], [MessageDeliverer],
 * and any [OutboxProcessorListener] -- is invoked concurrently from multiple worker threads, so all
 * of these must be safe for concurrent use. The store/deliverer implementations shipped in this
 * project already are; a custom implementation must be too.
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

    // Spun up lazily, only if concurrency > 1 -- accessed from both the scheduler thread (tick())
    // and the caller thread (stop()), so this must use the default thread-safe SYNCHRONIZED mode.
    // concurrency == 1 never touches this at all (see tick()), so it's never initialized then.
    private val workers: Lazy<ExecutorService> = lazy { config.workerExecutorFactory(config.concurrency) }

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
        shutdownAndAwait(scheduler)
        if (workers.isInitialized()) {
            shutdownAndAwait(workers.value)
        }
        logger.info("Outbox processor stopped")
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Shuts down [executor] and waits up to [SHUTDOWN_TIMEOUT_SECONDS] for in-flight tasks to
     * finish. If that times out, `shutdownNow()` forces it, then waits once more (same budget) so
     * `stop()` doesn't return while a custom [OutboxSchedulerConfig.workerExecutorFactory]'s
     * (potentially non-daemon) threads are still winding down -- logging if they still haven't by
     * then, since nothing more can be done short of abandoning the executor. If the caller is
     * interrupted at any point in this sequence, the flag is restored and [executor] is
     * force-shut-down via `shutdownNow()` without a further wait, so `stop()` still completes
     * deterministically and promptly honors the interrupt instead of leaking a partially-shut-down
     * executor.
     */
    private fun shutdownAndAwait(executor: ExecutorService) {
        executor.shutdown()
        try {
            if (executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return
            executor.shutdownNow()
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate after shutdownNow(); some worker threads may still be running")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while awaiting executor termination; forcing shutdownNow()", e)
            executor.shutdownNow()
        }
    }

    private fun tick() {
        if (config.concurrency == 1) {
            // Skips workers/Lazy entirely -- not just an inline no-op path -- so this configuration
            // truly pays no executor/synchronization overhead per tick, matching the class KDoc.
            processBatch()
            return
        }
        val pool = try {
            workers.value
        } catch (e: Exception) {
            // workers.value runs OutboxSchedulerConfig.workerExecutorFactory on first access; a
            // custom factory can fail (e.g. resource exhaustion). Uncaught, that would escape tick()
            // and make scheduleWithFixedDelay suppress all future ticks. Lazy retries its
            // initializer on a failed attempt, so the next tick tries workerExecutorFactory again.
            // Note: this does not prevent an OutOfMemoryError if OS thread creation is exhausted --
            // that's an Error, not an Exception, and is intentionally left uncaught here.
            logger.error("Failed to initialize outbox worker pool, will retry at next scheduled interval", e)
            return
        }
        val futures = (1..config.concurrency).mapNotNull { submitWorker(pool) }
        futures.forEach(::awaitWorker)
    }

    /**
     * Submits one worker's [processBatch] without letting [RejectedExecutionException] escape
     * [tick] -- a custom [OutboxSchedulerConfig.workerExecutorFactory] may return an executor that
     * can reject submissions (bounded queue, shutdown executor), and an uncaught exception here
     * would make `scheduleWithFixedDelay` suppress all future ticks.
     */
    private fun submitWorker(pool: ExecutorService): Future<*>? = try {
        pool.submit(::processBatch)
    } catch (e: RejectedExecutionException) {
        logger.error("Outbox worker submission rejected, will retry at next scheduled interval", e)
        null
    }

    /**
     * Awaits one worker's completion without letting anything escape [tick] -- an uncaught
     * exception here would make `scheduleWithFixedDelay` suppress all future ticks, silently
     * killing the scheduler. [processBatch] already catches [Exception] internally, so this
     * only guards against an [Error] surfacing via [ExecutionException], a [CancellationException]
     * if a custom [OutboxSchedulerConfig.workerExecutorFactory] cancels queued/running tasks (e.g.
     * as a backpressure strategy), or an interrupt while awaiting.
     *
     * An [InterruptedException] only aborts the wait when [running] is already `false` -- i.e.
     * when [stop] itself triggered the interrupt via `scheduler.shutdownNow()`. Any other
     * interrupt is swallowed and the wait resumes, because bailing out early here would let the
     * next scheduled tick start while this worker is still running, violating the class-level
     * guarantee that ticks never overlap.
     */
    private fun awaitWorker(future: Future<*>) {
        while (true) {
            try {
                future.get()
                return
            } catch (e: InterruptedException) {
                if (!running.get()) {
                    Thread.currentThread().interrupt()
                    logger.warn("Interrupted while awaiting outbox worker during shutdown", e)
                    return
                }
                // Not shutting down: keep waiting instead of letting the next tick overlap this worker.
            } catch (e: ExecutionException) {
                logger.error("Outbox worker failed unexpectedly, will retry at next scheduled interval", e.cause ?: e)
                return
            } catch (e: CancellationException) {
                logger.error("Outbox worker was cancelled, will retry at next scheduled interval", e)
                return
            }
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
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
