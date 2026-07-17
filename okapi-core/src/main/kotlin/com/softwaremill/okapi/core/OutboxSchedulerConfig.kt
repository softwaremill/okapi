package com.softwaremill.okapi.core

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class OutboxSchedulerConfig(
    val interval: Duration = Duration.ofSeconds(1),
    val batchSize: Int = 10,
    /**
     * Number of parallel workers fanned out per scheduler tick. Each worker claims its own
     * batch via [OutboxStore.claimPending]'s `FOR UPDATE SKIP LOCKED`, so workers never
     * compete for the same rows. `1` (default) preserves the original single-worker tick
     * with zero pooling overhead.
     */
    val concurrency: Int = 1,
    /**
     * Builds the [ExecutorService] used to run workers when [concurrency] > 1. Defaults to
     * a fixed platform-thread pool ([defaultPlatformPool]); pass [virtualThreadPool] for
     * high concurrency (roughly 32+) to avoid platform-thread context-switch overhead.
     */
    val workerExecutorFactory: (Int) -> ExecutorService = ::defaultPlatformPool,
) {
    init {
        require(!interval.isNegative && interval.toMillis() > 0) { "interval must be at least 1ms, got: $interval" }
        require(batchSize > 0) { "batchSize must be positive, got: $batchSize" }
        require(concurrency in 1..256) { "concurrency must be between 1 and 256, got: $concurrency" }
    }

    companion object {
        /** Fixed pool of [n] daemon platform threads, named `outbox-worker-N`. */
        @JvmStatic
        fun defaultPlatformPool(n: Int): ExecutorService {
            val counter = AtomicInteger(0)
            return Executors.newFixedThreadPool(n) { r ->
                Thread(r, "outbox-worker-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        /**
         * One virtual thread per submitted task. [n] is accepted for signature parity with
         * [defaultPlatformPool] but unused — virtual thread creation is unbounded.
         */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun virtualThreadPool(n: Int): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    }
}
