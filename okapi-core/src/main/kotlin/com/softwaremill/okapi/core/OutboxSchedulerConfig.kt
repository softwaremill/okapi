package com.softwaremill.okapi.core

import java.time.Duration

data class OutboxSchedulerConfig(
    val interval: Duration = Duration.ofSeconds(1),
    val batchSize: Int = 10,
) {
    init {
        require(!interval.isZero && !interval.isNegative) { "interval must be positive, got: $interval" }
        require(interval.toMillis() > 0) { "interval must be at least 1ms, got: $interval" }
        require(batchSize > 0) { "batchSize must be positive, got: $batchSize" }
    }
}
