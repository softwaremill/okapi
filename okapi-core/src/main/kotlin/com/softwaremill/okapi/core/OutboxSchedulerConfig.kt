package com.softwaremill.okapi.core

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class OutboxSchedulerConfig @JvmOverloads constructor(
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
     * a fixed platform-thread pool ([defaultPlatformPool]). [virtualThreadPool] is available
     * for workloads at concurrency well beyond what's benchmarked here; at the concurrency
     * levels actually measured (up to 64, see `benchmarks/results-postopt-KOJAK-77.md`)
     * platform threads were tied-or-ahead throughout, so nothing in that data recommends
     * switching away from the default.
     */
    val workerExecutorFactory: (Int) -> ExecutorService = ::defaultPlatformPool,
    /**
     * Upper bound on the batches each worker processes per tick (issue #132). A worker keeps
     * claiming, each batch in its own transaction, while the previous batch was full and made
     * progress, so [interval] only sets the idle polling delay and a backlog drains as fast as the
     * database and transports allow. `1` restores the 1.x behaviour of one batch per worker per
     * tick, which caps throughput at about `concurrency × batchSize / interval`.
     */
    val maxBatchesPerTick: Int = DEFAULT_MAX_BATCHES_PER_TICK,
) {
    init {
        require(!interval.isNegative && interval.toMillis() > 0) { "interval must be at least 1ms, got: $interval" }
        require(batchSize > 0) { "batchSize must be positive, got: $batchSize" }
        require(concurrency in 1..256) { "concurrency must be between 1 and 256, got: $concurrency" }
        require(maxBatchesPerTick > 0) { "maxBatchesPerTick must be positive, got: $maxBatchesPerTick" }
    }

    companion object {
        const val DEFAULT_MAX_BATCHES_PER_TICK = 1000

        /** Fixed pool of [n] daemon platform threads, named `outbox-worker-N`. */
        @JvmStatic
        fun defaultPlatformPool(n: Int): ExecutorService {
            require(n > 0) { "n must be positive, got: $n" }
            val counter = AtomicInteger(0)
            return Executors.newFixedThreadPool(n) { r ->
                Thread(r, "outbox-worker-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        /**
         * One virtual thread per submitted task. [n] is accepted for signature parity with
         * [defaultPlatformPool] but otherwise unused — virtual thread creation is unbounded --
         * still validated so a bad `concurrency` value fails the same way for either factory.
         */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun virtualThreadPool(n: Int): ExecutorService {
            require(n > 0) { "n must be positive, got: $n" }
            return Executors.newVirtualThreadPerTaskExecutor()
        }
    }
}
