package com.softwaremill.okapi.springboot

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "okapi")
@Validated
data class OkapiProperties(
    val datasourceQualifier: String? = null,
)
