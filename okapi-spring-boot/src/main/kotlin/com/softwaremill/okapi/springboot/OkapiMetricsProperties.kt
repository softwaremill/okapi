package com.softwaremill.okapi.springboot

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for Okapi Micrometer metrics.
 *
 * @property refreshInterval How often gauge metrics (`okapi.entries.count`, `okapi.entries.lag.seconds`)
 *   are refreshed from the outbox store. Each refresh runs one transaction with two queries.
 *   Set under property `okapi.metrics.refresh-interval`, e.g. `PT15S`, `30s`, `1m`. Default: 15 seconds.
 */
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
