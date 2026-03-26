package com.softwaremill.okapi.springboot

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "okapi.processor")
@Validated
data class OutboxProcessorProperties(
    @field:Min(1) val intervalMs: Long = 1_000,
    @field:Min(1) val batchSize: Int = 10,
)
