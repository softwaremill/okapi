package com.softwaremill.okapi.springboot

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "okapi.processor")
data class OutboxProcessorProperties(
    val interval: Duration = Duration.ofSeconds(1),
    val batchSize: Int = 10,
    val maxRetries: Int = 5,
) {
    init {
        require(!interval.isNegative && interval.toMillis() > 0) { "interval must be at least 1ms" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
    }
}
