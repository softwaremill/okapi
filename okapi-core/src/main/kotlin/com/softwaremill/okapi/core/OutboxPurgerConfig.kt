package com.softwaremill.okapi.core

import java.time.Duration

data class OutboxPurgerConfig(
    val retention: Duration = Duration.ofDays(7),
    val interval: Duration = Duration.ofHours(1),
    val batchSize: Int = 100,
) {
    init {
        require(!retention.isZero && !retention.isNegative) { "retention must be positive, got: $retention" }
        require(!interval.isZero && !interval.isNegative) { "interval must be positive, got: $interval" }
        require(batchSize > 0) { "batchSize must be positive, got: $batchSize" }
    }
}
