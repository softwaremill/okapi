package com.softwaremill.okapi.springboot

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "okapi.metrics")
data class OkapiMetricsProperties(
    val refreshInterval: Duration = Duration.ofSeconds(15),
) {
    init {
        require(!refreshInterval.isNegative && refreshInterval.toMillis() > 0) {
            "refreshInterval must be at least 1ms"
        }
    }
}
