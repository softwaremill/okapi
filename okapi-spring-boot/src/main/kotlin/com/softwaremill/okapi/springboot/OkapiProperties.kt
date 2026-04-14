package com.softwaremill.okapi.springboot

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "okapi")
@Validated
data class OkapiProperties(
    val datasourceQualifier: String? = null,
) {
    init {
        require(datasourceQualifier == null || datasourceQualifier.isNotBlank()) {
            "okapi.datasource-qualifier must not be blank. Set it to the bean name of the outbox DataSource, or remove the property."
        }
    }
}
