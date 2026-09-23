package com.softwaremill.okapi.springboot

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
) {
    init {
        require(!interval.isNegative && interval.toMillis() > 0) { "interval must be at least 1ms" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(concurrency in 1..256) { "concurrency must be between 1 and 256" }
    }
}
