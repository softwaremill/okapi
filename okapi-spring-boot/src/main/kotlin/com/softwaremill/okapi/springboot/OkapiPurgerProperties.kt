package com.softwaremill.okapi.springboot

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "okapi.purger")
@Validated
data class OkapiPurgerProperties(
    @field:Min(1) val retentionDays: Long = 7,
    @field:Min(1) val intervalMinutes: Long = 60,
    @field:Min(1) val batchSize: Int = 100,
)
