package com.softwaremill.okapi.core

import org.slf4j.LoggerFactory
import java.time.Duration

data class OutboxPurgerConfig(
    val retention: Duration = Duration.ofDays(7),
    val interval: Duration = Duration.ofHours(1),
    val batchSize: Int = 100,
) {
    init {
        require(!retention.isZero && !retention.isNegative) { "retention must be positive, got: $retention" }
        require(!interval.isZero && !interval.isNegative) { "interval must be positive, got: $interval" }
        require(interval.toMillis() > 0) { "interval must be at least 1ms, got: $interval" }
        require(batchSize > 0) { "batchSize must be positive, got: $batchSize" }

        if (interval < Duration.ofSeconds(1)) {
            logger.warn("Purger interval is very low ({}). Did you forget the time unit suffix (e.g., '60m' not '60')?", interval)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxPurgerConfig::class.java)
    }
}
