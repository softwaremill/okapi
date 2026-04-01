package com.softwaremill.okapi.springboot

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "okapi.purger")
data class OutboxPurgerProperties(
    val retention: Duration = Duration.ofDays(7),
    val interval: Duration = Duration.ofHours(1),
    val batchSize: Int = 100,
) {
    init {
        require(!retention.isZero && !retention.isNegative) { "retention must be positive" }
        require(!interval.isZero && !interval.isNegative) { "interval must be positive" }
        require(interval.toMillis() > 0) { "interval must be at least 1ms, got: $interval" }
        require(batchSize > 0) { "batchSize must be positive" }
    }
}
