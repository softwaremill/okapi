package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxSchedulerConfig
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.core.TransportDispatch
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "okapi.processor")
data class OutboxProcessorProperties @JvmOverloads constructor(
    val interval: Duration = Duration.ofSeconds(1),
    val batchSize: Int = 10,
    val maxRetries: Int = 5,
    /**
     * Number of parallel workers fanned out per scheduler tick, each claiming its own batch
     * via `FOR UPDATE SKIP LOCKED`. See [com.softwaremill.okapi.core.OutboxSchedulerConfig].
     */
    val concurrency: Int = 1,
    /**
     * How a batch spanning several `MessageDeliverer` beans is dispatched: `parallel` (default,
     * one virtual thread per transport group) or `sequential` (one group after another on the
     * calling thread). Only applies when 2+ deliverer beans are present — a single deliverer is
     * never wrapped in a `CompositeMessageDeliverer`. See
     * [com.softwaremill.okapi.core.TransportDispatch].
     */
    val transportDispatch: TransportDispatch = TransportDispatch.PARALLEL,
    /** Delay before the first retry of a retriable failure. See [com.softwaremill.okapi.core.RetryPolicy]. */
    val initialBackoff: Duration = RetryPolicy.DEFAULT_INITIAL_BACKOFF,
    /** Factor applied to the delay after each further retry. */
    val backoffMultiplier: Double = RetryPolicy.DEFAULT_BACKOFF_MULTIPLIER,
    /** Upper bound on the delay between retries. */
    val maxBackoff: Duration = RetryPolicy.DEFAULT_MAX_BACKOFF,
    /** Fraction (0 to 1) by which each delay is shortened at random, to spread retries out. */
    val backoffJitter: Double = RetryPolicy.DEFAULT_JITTER,
    /**
     * Maximum batches each worker processes per tick while a backlog remains; `1` restores the 1.x
     * one batch per tick. See [com.softwaremill.okapi.core.OutboxSchedulerConfig.maxBatchesPerTick].
     */
    val maxBatchesPerTick: Int = OutboxSchedulerConfig.DEFAULT_MAX_BATCHES_PER_TICK,
) {
    init {
        require(!interval.isNegative && interval.toMillis() > 0) { "interval must be at least 1ms" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(concurrency in 1..256) { "concurrency must be between 1 and 256" }
        require(maxBatchesPerTick > 0) { "maxBatchesPerTick must be positive" }
        // Fail at startup, with the property names, rather than when the RetryPolicy is built.
        require(!initialBackoff.isNegative) { "initialBackoff must be >= 0" }
        require(backoffMultiplier >= 1.0 && backoffMultiplier.isFinite()) { "backoffMultiplier must be finite and >= 1" }
        require(maxBackoff >= initialBackoff) { "maxBackoff must be >= initialBackoff" }
        require(maxBackoff <= RetryPolicy.MAX_ALLOWED_BACKOFF) { "maxBackoff must be at most ${RetryPolicy.MAX_ALLOWED_BACKOFF}" }
        require(backoffJitter in 0.0..1.0) { "backoffJitter must be between 0 and 1" }
    }
}
